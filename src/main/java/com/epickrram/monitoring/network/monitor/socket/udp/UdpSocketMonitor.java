package com.epickrram.monitoring.network.monitor.socket.udp;

import com.epickrram.monitoring.network.monitor.socket.SocketIdentifier;
import com.epickrram.monitoring.network.monitor.util.DelimitedDataParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class UdpSocketMonitor
{
    // TODO replace with Long2ObjectMap
    private final AtomicReference<Map<Long, UdpBufferStats>> mapReference = new AtomicReference<>(new HashMap<>());

    private final UdpSocketMonitoringLifecycleListener lifecycleListener;
    private final Path pathToProcNetUdp;
    private final DelimitedDataParser columnParser = new DelimitedDataParser(new ColumnHandler(this::handleEntry), (byte)' ', true);
    private final DelimitedDataParser lineParser = new DelimitedDataParser(columnParser, (byte)'\n', true);

    private ByteBuffer buffer = ByteBuffer.allocateDirect(65536);
    private FileChannel fileChannel;
    private UdpSocketStatisticsHandler statisticsHandler;
    private Map<Long, UdpBufferStats> monitoredSocketsSnapshot;
    private long updateCount = 0;

    public UdpSocketMonitor(final UdpSocketMonitoringLifecycleListener lifecycleListener, final Path pathToProcNetUdp)
    {
        this.lifecycleListener = lifecycleListener;
        this.pathToProcNetUdp = pathToProcNetUdp;
    }

    private void handleEntry(final BufferStatsEntry entry)
    {
        final UdpBufferStats lastUpdate = monitoredSocketsSnapshot.get(entry.getSocketIdentifier());
        if(lastUpdate != null)
        {
            lastUpdate.updateFrom(entry);
            lastUpdate.updateCount(updateCount);
            if(lastUpdate.hasChanged())
            {
                statisticsHandler.onStatisticsUpdated(lastUpdate.socketAddress, entry.getSocketIdentifier(),
                        entry.getInode(), entry.getReceiveQueueDepth(), entry.getDrops());
            }
        }
    }

    public void poll(final UdpSocketStatisticsHandler handler)
    {
        this.statisticsHandler = handler;
        this.monitoredSocketsSnapshot = mapReference.get();
        try
        {
            if(fileChannel == null)
            {
                fileChannel = FileChannel.open(pathToProcNetUdp, StandardOpenOption.READ);
            }
            final long fileSize = Files.size(pathToProcNetUdp);

            if(fileSize > buffer.capacity())
            {
                buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2);
            }

            buffer.clear();
            fileChannel.read(buffer, 0);
            buffer.flip();

            lineParser.reset();
            lineParser.handleToken(buffer, buffer.position(), buffer.limit());
        }
        catch(IOException e)
        {
            throw new UncheckedIOException(e);
        }
        finally
        {
            this.statisticsHandler = null;
        }

        purgeStaleEntries();

        updateCount++;
    }

    public void beginMonitoringOf(final InetSocketAddress socketAddress)
    {
        final Map<Long, UdpBufferStats> currentSockets = mapReference.get();
        final long socketIdentifier = SocketIdentifier.fromInet4SocketAddress(socketAddress);

        if(currentSockets.containsKey(socketIdentifier))
        {
            return;
        }
        while(true)
        {
            final Map<Long, UdpBufferStats> snapshot = mapReference.get();
            final Map<Long, UdpBufferStats> updatedSockets = new HashMap<>(currentSockets.size());
            updatedSockets.putAll(currentSockets);
            updatedSockets.put(socketIdentifier, new UdpBufferStats(socketAddress));
            if(mapReference.compareAndSet(snapshot, updatedSockets))
            {
                break;
            }
        }
        lifecycleListener.socketMonitoringStarted(socketAddress);
    }

    public void endMonitoringOf(final InetSocketAddress socketAddress)
    {
        final Map<Long, UdpBufferStats> currentSockets = mapReference.get();
        final long socketIdentifier = SocketIdentifier.fromInet4SocketAddress(socketAddress);

        if(!currentSockets.containsKey(socketIdentifier))
        {
            return;
        }
        while(true)
        {
            final Map<Long, UdpBufferStats> snapshot = mapReference.get();
            final Map<Long, UdpBufferStats> updatedSockets = new HashMap<>(currentSockets.size());
            updatedSockets.putAll(snapshot);
            updatedSockets.remove(socketIdentifier);
            if(mapReference.compareAndSet(snapshot, updatedSockets))
            {
                break;
            }
        }
        lifecycleListener.socketMonitoringStopped(socketAddress);
    }

    private void purgeStaleEntries()
    {
        final Set<Long> keysForRemoval = new HashSet<>();
        for(final Long key : monitoredSocketsSnapshot.keySet())
        {
            if(monitoredSocketsSnapshot.get(key).getUpdateCount() != updateCount)
            {
                keysForRemoval.add(key);
            }
        }

        while(true)
        {
            final Set<InetSocketAddress> removedAddresses = new HashSet<>();
            final Map<Long, UdpBufferStats> snapshot = mapReference.get();
            final Map<Long, UdpBufferStats> updatedSockets = new HashMap<>(snapshot.size());
            updatedSockets.putAll(snapshot);

            for(final Long key : keysForRemoval)
            {
                removedAddresses.add(updatedSockets.remove(key).socketAddress);
            }
            if(mapReference.compareAndSet(snapshot, updatedSockets))
            {
                for(final InetSocketAddress removedAddress : removedAddresses)
                {
                    lifecycleListener.socketMonitoringStopped(removedAddress);
                }
                break;
            }
        }
    }

    private static final class UdpBufferStats
    {
        private long receiveQueueDepth = -1;
        private long drops = -1;
        private boolean changed;
        private InetSocketAddress socketAddress;
        private long updateCount = -1;

        UdpBufferStats(final InetSocketAddress socketAddress)
        {
            this.socketAddress = socketAddress;
        }

        void updateFrom(final BufferStatsEntry entry)
        {
            changed = (this.receiveQueueDepth != entry.getReceiveQueueDepth()) ||
                    (this.drops != entry.getDrops());
            this.receiveQueueDepth = entry.getReceiveQueueDepth();
            this.drops = entry.getDrops();
        }

        boolean hasChanged()
        {
            return changed;
        }

        void updateCount(final long updateCount)
        {
            this.updateCount = updateCount;
        }

        long getUpdateCount()
        {
            return updateCount;
        }
    }
}