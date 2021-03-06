package org.infinispan.remoting.inboundhandler;

import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.remote.CacheRpcCommand;
import org.infinispan.commands.remote.MultipleRpcCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.VersionedPrepareCommand;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.remoting.inboundhandler.action.ActionListener;
import org.infinispan.remoting.inboundhandler.action.ActionState;
import org.infinispan.remoting.inboundhandler.action.CheckTopologyAction;
import org.infinispan.remoting.inboundhandler.action.DefaultReadyAction;
import org.infinispan.remoting.inboundhandler.action.LockAction;
import org.infinispan.remoting.inboundhandler.action.PendingTxAction;
import org.infinispan.remoting.inboundhandler.action.ReadyAction;
import org.infinispan.statetransfer.StateRequestCommand;
import org.infinispan.transaction.LockingMode;
import org.infinispan.util.concurrent.BlockingRunnable;
import org.infinispan.util.concurrent.locks.LockManager;
import org.infinispan.util.concurrent.locks.PendingLockManager;
import org.infinispan.util.concurrent.locks.TransactionalRemoteLockCommand;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.infinispan.commons.util.InfinispanCollections.forEach;

/**
 * A {@link PerCacheInboundInvocationHandler} implementation for non-total order caches.
 *
 * @author Pedro Ruivo
 * @since 7.1
 */
public class NonTotalOrderTxPerCacheInboundInvocationHandler extends BasePerCacheInboundInvocationHandler {

   private static final Log log = LogFactory.getLog(NonTotalOrderTxPerCacheInboundInvocationHandler.class);
   private static final boolean trace = log.isTraceEnabled();

   private final CheckTopologyAction checkTopologyAction;

   private LockManager lockManager;
   private ClusteringDependentLogic clusteringDependentLogic;
   private PendingLockManager pendingLockManager;
   private Configuration configuration;
   private boolean pessimisticLocking;

   public NonTotalOrderTxPerCacheInboundInvocationHandler() {
      checkTopologyAction = new CheckTopologyAction(this);
   }

   @Inject
   public void inject(LockManager lockManager, ClusteringDependentLogic clusteringDependentLogic, Configuration configuration,
                      PendingLockManager pendingLockManager) {
      this.lockManager = lockManager;
      this.clusteringDependentLogic = clusteringDependentLogic;
      this.configuration = configuration;
      this.pendingLockManager = pendingLockManager;
      this.pessimisticLocking = configuration.transaction().lockingMode() == LockingMode.PESSIMISTIC;
   }

   @Override
   public void handle(CacheRpcCommand command, Reply reply, DeliverOrder order) {
      if (order == DeliverOrder.TOTAL) {
         unexpectedDeliverMode(command, order);
      }
      try {
         final int commandTopologyId = extractCommandTopologyId(command);
         final boolean onExecutorService = executeOnExecutorService(order, command);
         final BlockingRunnable runnable;

         switch (command.getCommandId()) {
            case MultipleRpcCommand.COMMAND_ID:
               runnable = createReadyActionRunnable(command, reply, commandTopologyId, true, onExecutorService,
                                                    createReadyActionForMultipleRpcCommand((MultipleRpcCommand) command));
               break;
            case PrepareCommand.COMMAND_ID:
            case VersionedPrepareCommand.COMMAND_ID:
               if (pessimisticLocking) {
                  runnable = createDefaultRunnable(command, reply, commandTopologyId, true, onExecutorService);
               } else {
                  runnable = createReadyActionRunnable(command, reply, commandTopologyId, true, onExecutorService,
                                                       createReadyAction(commandTopologyId, (PrepareCommand) command));
               }
               break;
            case LockControlCommand.COMMAND_ID:
               runnable = createReadyActionRunnable(command, reply, commandTopologyId, true, onExecutorService,
                                                    createReadyAction(commandTopologyId, (LockControlCommand) command));
               break;
            default:
               runnable = createDefaultRunnable(command, reply, commandTopologyId, command.getCommandId() != StateRequestCommand.COMMAND_ID, onExecutorService);
               break;
         }
         handleRunnable(runnable, onExecutorService);
      } catch (Throwable throwable) {
         reply.reply(exceptionHandlingCommand(command, throwable));
      }
   }

   @Override
   protected Log getLog() {
      return log;
   }

   @Override
   protected boolean isTraceEnabled() {
      return trace;
   }

   protected final BlockingRunnable createReadyActionRunnable(CacheRpcCommand command, Reply reply, int commandTopologyId,
                                                              boolean waitTransactionalData, boolean onExecutorService,
                                                              ReadyAction readyAction) {
      final TopologyMode topologyMode = TopologyMode.create(onExecutorService, waitTransactionalData);
      if (onExecutorService && readyAction != null) {
         readyAction.addListener(remoteCommandsExecutor::checkForReadyTasks);
         return new DefaultTopologyRunnable(this, command, reply, topologyMode, commandTopologyId) {
            @Override
            public boolean isReady() {
               return super.isReady() && readyAction.isReady();
            }
         };
      } else {
         return new DefaultTopologyRunnable(this, command, reply, topologyMode, commandTopologyId);
      }
   }

   private ReadyAction createReadyAction(int topologyId, TransactionalRemoteLockCommand command) {
      if (command.hasSkipLocking()) {
         return null;
      }
      Collection<Object> keys = command.getKeysToLock();
      if (keys.isEmpty()) {
         return null;
      }
      final long timeoutMillis = command.hasZeroLockAcquisition() ? 0 : configuration.locking().lockAcquisitionTimeout();

      DefaultReadyAction action = new DefaultReadyAction(new ActionState(command, topologyId, timeoutMillis),
                                                         checkTopologyAction,
                                                         new PendingTxAction(pendingLockManager, clusteringDependentLogic),
                                                         new LockAction(lockManager, clusteringDependentLogic));
      action.registerListener();
      return action;
   }

   private ReadyAction createReadyActionForMultipleRpcCommand(MultipleRpcCommand command) {
      ReplicableCommand[] commands = command.getCommands();
      List<ReadyAction> list = new ArrayList<>(commands.length);
      forEach(commands, cmd -> {
         if (cmd instanceof LockControlCommand) {
            ReadyAction action = createReadyAction(((LockControlCommand) cmd).getTopologyId(), (LockControlCommand) cmd);
            if (action != null) {
               list.add(action);
            }
         } else if (!pessimisticLocking && cmd instanceof PrepareCommand) {
            ReadyAction action = createReadyAction(((PrepareCommand) cmd).getTopologyId(), (PrepareCommand) cmd);
            if (action != null) {
               list.add(action);
            }
         }
      });

      if (list.isEmpty()) {
         return null;
      } else if (list.size() == 1) {
         return list.get(0);
      }

      CompositeAction action = new CompositeAction(list);
      action.registerListener();
      return action;
   }

   private static class CompositeAction implements ReadyAction, ActionListener {

      private final Collection<ReadyAction> actions;
      private final AtomicBoolean notify;
      private volatile ActionListener listener;

      private CompositeAction(Collection<ReadyAction> actions) {
         this.actions = actions;
         notify = new AtomicBoolean(false);
      }

      public void registerListener() {
         actions.forEach(readyAction -> readyAction.addListener(this));
      }

      @Override
      public boolean isReady() {
         for (ReadyAction action : actions) {
            if (!action.isReady()) {
               return false;
            }
         }
         return true;
      }

      @Override
      public void addListener(ActionListener listener) {
         this.listener = listener;
      }

      @Override
      public void onComplete() {
         ActionListener actionListener = listener;
         if (isReady() && actionListener != null && notify.compareAndSet(false, true)) {
            actionListener.onComplete();
         }
      }
   }
}
