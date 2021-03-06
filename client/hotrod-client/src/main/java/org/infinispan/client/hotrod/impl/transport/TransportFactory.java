package org.infinispan.client.hotrod.impl.transport;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

import org.infinispan.client.hotrod.CacheTopologyInfo;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.event.ClientListenerNotifier;
import org.infinispan.client.hotrod.impl.consistenthash.ConsistentHash;
import org.infinispan.client.hotrod.impl.consistenthash.ConsistentHashFactory;
import org.infinispan.client.hotrod.impl.protocol.Codec;

/**
 * Transport factory for building and managing {@link org.infinispan.client.hotrod.impl.transport.Transport} objects.
 *
 * @author Mircea.Markus@jboss.com
 * @since 4.1
 */
public interface TransportFactory {

   Transport getTransport(Set<SocketAddress> failedServers, byte[] cacheName);

   Transport getAddressTransport(SocketAddress server);

   void releaseTransport(Transport transport);

   void start(Codec codec, Configuration configuration, AtomicInteger topologyId, ClientListenerNotifier listenerNotifier);

   void updateServers(Collection<SocketAddress> newServers, byte[] cacheName, boolean quiet);

   void destroy();

   CacheTopologyInfo getCacheTopologyInfo(byte[] cacheName);

   /**
    * @deprecated Only called for Hot Rod 1.x protocol.
    */
   @Deprecated
   void updateHashFunction(Map<SocketAddress, Set<Integer>> servers2Hash, int numKeyOwners, short hashFunctionVersion, int hashSpace, byte[] cacheName);

   void updateHashFunction(SocketAddress[][] segmentOwners, int numSegments, short hashFunctionVersion, byte[] cacheName);

   ConsistentHash getConsistentHash(byte[] cacheName);

   ConsistentHashFactory getConsistentHashFactory();

   Transport getTransport(byte[] key, Set<SocketAddress> failedServers, byte[] cacheName);

   boolean isTcpNoDelay();

   boolean isTcpKeepAlive();

   int getMaxRetries();

   int getSoTimeout();

   int getConnectTimeout();

   void invalidateTransport(SocketAddress serverAddress, Transport transport);

   SSLContext getSSLContext();

   void reset(byte[] cacheName);

   boolean trySwitchCluster(byte[] cacheName);
}
