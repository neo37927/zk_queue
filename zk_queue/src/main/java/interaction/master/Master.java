package interaction.master;

import com.google.common.base.Preconditions;
import interaction.protocol.ConnectedComponent;
import interaction.protocol.InteractionProtocol;
import interaction.protocol.MasterQueue;
import interaction.util.KattaException;
import interaction.util.MasterConfiguration;
import org.I0Itec.zkclient.NetworkUtil;
import org.I0Itec.zkclient.ZkServer;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.UUID;

/**
 * Created by xiaolin  on 2017/4/1.
 */
public class Master implements ConnectedComponent {

    protected final static Logger LOG = Logger.getLogger(Master.class);

    protected volatile OperatorThread _operatorThread;

    private String _masterName;
    private ZkServer _zkServer;
    private boolean _shutdownClient;
    protected InteractionProtocol _protocol;

    private IDeployPolicy _deployPolicy;
    private long _safeModeMaxTime;

    public Master(InteractionProtocol interactionProtocol, ZkServer zkServer) throws KattaException {
        this(interactionProtocol, false);
        _zkServer = zkServer;
    }

    public Master(InteractionProtocol interactionProtocol, boolean shutdownClient) throws KattaException {
        this(interactionProtocol, shutdownClient, new MasterConfiguration());
    }

    @SuppressWarnings("unchecked")
    public Master(InteractionProtocol protocol, boolean shutdownClient, MasterConfiguration masterConfiguration)
            throws KattaException {
        _protocol = protocol;
        _masterName = NetworkUtil.getLocalhostName() + "_" + UUID.randomUUID().toString();
        _shutdownClient = shutdownClient;
        protocol.registerComponent(this);
        final String deployPolicyClassName = masterConfiguration.getDeployPolicy();
        try {
            final Class<IDeployPolicy> policyClazz = (Class<IDeployPolicy>) Class.forName(deployPolicyClassName);
            _deployPolicy = policyClazz.newInstance();
        } catch (final Exception e) {
            throw new KattaException("Unable to instantiate deploy policy", e);
        }

        _safeModeMaxTime = masterConfiguration.getInt(MasterConfiguration.SAFE_MODE_MAX_TIME);
    }

    public synchronized void start() {
        Preconditions.checkState(!isShutdown(), "master was already shut-down");
        becomePrimaryOrSecondaryMaster();
    }

    public synchronized void reconnect() {
        disconnect();// just to be sure we do not open a 2nd operator thread
        becomePrimaryOrSecondaryMaster();
    }

    public synchronized void disconnect() {
        if (isMaster()) {
            _operatorThread.interrupt();
            try {
                _operatorThread.join();
            } catch (InterruptedException e) {
                Thread.interrupted();
                // proceed
            }
            _operatorThread = null;
        }
    }

    private synchronized void becomePrimaryOrSecondaryMaster() {
        if (isShutdown()) {
            return;
        }
        MasterQueue queue = _protocol.publishMaster(this);
        if (queue != null) {
            UpgradeAction upgradeAction = UpgradeRegistry.findUpgradeAction(_protocol, Version.readFromJar());
            if (upgradeAction != null) {
                upgradeAction.upgrade(_protocol);
            }
            _protocol.setVersion(Version.readFromJar());
            LOG.info(getMasterName() + " became master with " + queue.size() + " waiting master operations");
            startNodeManagement();
            MasterContext masterContext = new MasterContext(_protocol, this, _deployPolicy, queue);
            _operatorThread = new OperatorThread(masterContext, _safeModeMaxTime);
            _operatorThread.start();
        }
    }

    public synchronized boolean isInSafeMode() {
        if (!isMaster()) {
            return true;
        }
        return _operatorThread.isInSafeMode();
    }

    public Collection<String> getConnectedNodes() {
        return _protocol.getLiveNodes();
    }

    public synchronized MasterContext getContext() {
        if (!isMaster()) {
            return null;
        }
        return _operatorThread.getContext();
    }

    public synchronized boolean isMaster() {
        return _operatorThread != null;
    }

    private synchronized boolean isShutdown() {
        return _protocol == null;
    }

    public String getMasterName() {
        return _masterName;
    }

    public void handleMasterDisappearedEvent() {
        becomePrimaryOrSecondaryMaster();
    }

    private void startNodeManagement() {
        LOG.info("start managing nodes...");
        List<String> nodes = _protocol.registerChildListener(this, PathDef.NODES_LIVE, new IAddRemoveListener() {
            @Override
            public void removed(String name) {
                synchronized (Master.this) {
                    if (!isInSafeMode()) {
                        _protocol.addMasterOperation(new CheckIndicesOperation());
                    }
                }
            }

            @Override
            public void added(String name) {
                synchronized (Master.this) {
                    if (!isMaster()) {
                        return;
                    }
                    _protocol.addMasterOperation(new RemoveObsoleteShardsOperation(name));
                    if (!isInSafeMode()) {
                        _protocol.addMasterOperation(new CheckIndicesOperation());
                    }
                }
            }
        });
        _protocol.addMasterOperation(new CheckIndicesOperation());
        for (String node : nodes) {
            _protocol.addMasterOperation(new RemoveObsoleteShardsOperation(node));
        }
        LOG.info("found following nodes connected: " + nodes);
    }

    public synchronized void shutdown() {
        if (_protocol != null) {
            _protocol.unregisterComponent(this);
            if (isMaster()) {
                _operatorThread.interrupt();
                try {
                    _operatorThread.join();
                    _operatorThread = null;
                } catch (final InterruptedException e1) {
                    // proceed
                }
            }
            if (_shutdownClient) {
                _protocol.disconnect();
            }
            _protocol = null;
            if (_zkServer != null) {
                _zkServer.shutdown();
            }
        }
    }

}