package interaction.protocol;

import org.I0Itec.zkclient.ExceptionUtil;
import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkNoNodeException;

import java.io.Serializable;
import java.util.List;

/**
 * Created by xiaolin  on 2017/4/1.
 */
public class BlockingQueue <T extends Serializable> {

    protected static class Element<T> {
        private String _name;
        private T _data;

        public Element(String name, T data) {
            _name = name;
            _data = data;
        }

        public String getName() {
            return _name;
        }

        public T getData() {
            return _data;
        }
    }

    protected final ZkClient _zkClient;
    private final String _elementsPath;

    public BlockingQueue(ZkClient zkClient, String rootPath) {
        _zkClient = zkClient;
        _elementsPath = rootPath + "/operations";
        _zkClient.createPersistent(rootPath, true);
        _zkClient.createPersistent(_elementsPath, true);
    }

    private String getElementRoughPath() {
        return getElementPath("operation" + "-");
    }

    public String getElementPath(String elementId) {
        return _elementsPath + "/" + elementId;
    }

    /**
     *
     * @param element
     * @return the id of the element in the queue
     */
    public String add(T element) {
        try {
            String sequential = _zkClient.createPersistentSequential(getElementRoughPath(), element);
            String elementId = sequential.substring(sequential.lastIndexOf('/') + 1);
            return elementId;
        } catch (Exception e) {
            throw ExceptionUtil.convertToRuntimeException(e);
        }
    }

    public T remove() throws InterruptedException {
        Element<T> element = getFirstElement();
        _zkClient.delete(getElementPath(element.getName()));
        return element.getData();
    }

    public boolean containsElement(String elementId) {
        String zkPath = getElementPath(elementId);
        return _zkClient.exists(zkPath);
    }

    public T peek() throws InterruptedException {
        Element<T> element = getFirstElement();
        if (element == null) {
            return null;
        }
        return element.getData();
    }

    public int size() {
        return _zkClient.getChildren(_elementsPath).size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private String getSmallestElement(List<String> list) {
        String smallestElement = list.get(0);
        for (String element : list) {
            if (element.compareTo(smallestElement) < 0) {
                smallestElement = element;
            }
        }

        return smallestElement;
    }

    @SuppressWarnings("unchecked")
    protected Element<T> getFirstElement() throws InterruptedException {
        final Object mutex = new Object();
        IZkChildListener notifyListener = new IZkChildListener() {
            public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception {
                synchronized (mutex) {
                    mutex.notify();
                }
            }
        };
        try {
            while (true) {
                List<String> elementNames;
                synchronized (mutex) {
                    elementNames = _zkClient.subscribeChildChanges(_elementsPath, notifyListener);
                    while (elementNames == null || elementNames.isEmpty()) {
                        mutex.wait();
                        elementNames = _zkClient.getChildren(_elementsPath);
                    }
                }
                String elementName = getSmallestElement(elementNames);
                try {
                    String elementPath = getElementPath(elementName);
                    return new Element<T>(elementName, (T) _zkClient.readData(elementPath));
                } catch (ZkNoNodeException e) {
                    // somebody else picked up the element first, so we have to
                    // retry with the new first element
                }
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw ExceptionUtil.convertToRuntimeException(e);
        } finally {
            _zkClient.unsubscribeChildChanges(_elementsPath, notifyListener);
        }
    }

}