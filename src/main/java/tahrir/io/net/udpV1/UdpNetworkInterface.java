package tahrir.io.net.udpV1;

import java.io.IOException;
import java.net.*;
import java.security.interfaces.*;
import java.util.Map;
import java.util.concurrent.*;

import org.slf4j.LoggerFactory;

import tahrir.io.net.*;
import tahrir.tools.*;

import com.google.common.collect.Maps;

public class UdpNetworkInterface extends TrNetworkInterface<UdpRemoteAddress> {
	private static org.slf4j.Logger logger = LoggerFactory.getLogger(UdpNetworkInterface.class);

	private final PriorityBlockingQueue<QueuedPacket> outbox = new PriorityBlockingQueue<UdpNetworkInterface.QueuedPacket>();
	public static final int MAX_PACKET_SIZE_BYTES = 1450;
	private final DatagramSocket datagramSocket;
	final Config config;
	private final Sender sender;

	private final Receiver receiver;

	public final RSAPrivateKey myPrivateKey;

	public Map<UdpRemoteAddress, UdpRemoteConnection> remoteConnections = Maps.newConcurrentMap();

	public final RSAPublicKey myPublicKey;

	public static class Config {
		public int listenPort;

		public volatile int maxUpstreamBytesPerSecond;
	}

	public UdpNetworkInterface(final Config config, final Tuple2<RSAPublicKey, RSAPrivateKey> keyPair)
	throws SocketException {
		this.config = config;
		myPublicKey = keyPair.a;
		myPrivateKey = keyPair.b;
		datagramSocket = new DatagramSocket(config.listenPort);
		datagramSocket.setSoTimeout(500);
		sender = new Sender(this);
		sender.start();
		receiver = new Receiver(this);
		receiver.start();
	}

	private final ConcurrentLinkedQueue<TrMessageListener<UdpRemoteAddress>> listeners = new ConcurrentLinkedQueue<TrMessageListener<UdpRemoteAddress>>();

	private final ConcurrentMap<UdpRemoteAddress, TrMessageListener<UdpRemoteAddress>> listenersByAddress = Maps
	.newConcurrentMap();

	@Override
	public void registerListener(final TrMessageListener<UdpRemoteAddress> listener) {
		listeners.add(listener);
	}

	@Override
	protected void registerListenerForSender(final UdpRemoteAddress sender,
			final TrMessageListener<UdpRemoteAddress> listener) {
		if (listenersByAddress.put(sender, listener) != null) {
			logger.warn("Overwriting listener for sender {}", sender);
		}
	}


	@Override
	protected void unregisterListener(
			final tahrir.io.net.TrNetworkInterface.TrMessageListener<UdpRemoteAddress> listener) {
		listeners.remove(listener);
	}

	@Override
	public void sendTo(final UdpRemoteAddress recepient, final ByteArraySegment encryptedMessage,
			final tahrir.io.net.TrNetworkInterface.TrSentListener sentListener, final double priority) {
		assert encryptedMessage.length <= MAX_PACKET_SIZE_BYTES : "Packet length " + encryptedMessage.length
				+ " greater than " + MAX_PACKET_SIZE_BYTES;
		if (recepient.port == config.listenPort)
			throw new RuntimeException(); // REMOVEME
		final QueuedPacket qp = new QueuedPacket(recepient, encryptedMessage, sentListener, priority);
		outbox.add(qp);
	}

	private static class Receiver extends Thread {
		public volatile boolean active = true;

		private final UdpNetworkInterface parent;

		public Receiver(final UdpNetworkInterface parent) {
			this.parent = parent;
		}

		@Override
		public void run() {

			while (active) {
				final DatagramPacket dp = new DatagramPacket(new byte[UdpNetworkInterface.MAX_PACKET_SIZE_BYTES],
						UdpNetworkInterface.MAX_PACKET_SIZE_BYTES);
				try {
					parent.datagramSocket.receive(dp);

					final UdpRemoteAddress ura = new UdpRemoteAddress(dp.getAddress(), dp.getPort());
					final tahrir.io.net.TrNetworkInterface.TrMessageListener<UdpRemoteAddress> ml = parent.listenersByAddress.get(ura);

					if (ml != null) {
						try {
							ml.received(parent, ura, ByteArraySegment.from(dp));
						} catch (final Exception e) {
							parent.logger.error(
									"Error handling received UDP packet on port "
									+ parent.datagramSocket.getLocalPort() + " from port " + dp.getPort(), e);
						}
					} else {
						for (final tahrir.io.net.TrNetworkInterface.TrMessageListener<UdpRemoteAddress> li : parent.listeners) {
							li.received(parent, ura, ByteArraySegment.from(dp));
						}
					}
				} catch (final SocketTimeoutException e) {
					// NOOP
				} catch (final IOException e) {
					parent.logger.error("Error receiving udp packet on port " + parent.datagramSocket.getLocalPort()
							+ ", receiveractive=" + active, e);
				}
			}
			parent.datagramSocket.close();
		}
	}

	private static class Sender extends Thread {
		public volatile boolean active = true;
		private final UdpNetworkInterface parent;

		public Sender(final UdpNetworkInterface parent) {
			this.parent = parent;
		}

		@Override
		public void run() {
			while (active) {
				try {
					final long startTime = System.currentTimeMillis();
					final QueuedPacket packet = parent.outbox.poll(1, TimeUnit.SECONDS);
					if (packet != null) {
					}
					if (packet != null) {
						final DatagramPacket dp = new DatagramPacket(packet.data.array, packet.data.offset,
								packet.data.length,
								packet.addr.inetAddress, packet.addr.port);
						try {
							parent.datagramSocket.send(dp);
							if (packet.sentListener != null) {
								packet.sentListener.sent();
							}
						} catch (final IOException e) {
							if (packet.sentListener != null) {
								packet.sentListener.failure();
							}
							parent.logger.error("Failed to send UDP packet", e);
						}
						// System.out.println("Sent: " +
						// parent.config.listenPort + " -> " + packet.addr.port
						// + " len: "
						// + packet.data.length + " data: " + packet.data);
						Thread.sleep((1000l * packet.data.length / parent.config.maxUpstreamBytesPerSecond));
					}
				} catch (final InterruptedException e) {

				}
			}

		}
	}

	private static class QueuedPacket implements Comparable<QueuedPacket> {

		private final UdpRemoteAddress addr;
		private final ByteArraySegment data;
		private final double priority;
		private final tahrir.io.net.TrNetworkInterface.TrSentListener sentListener;

		public QueuedPacket(final UdpRemoteAddress addr, final ByteArraySegment encryptedMessage,
				final tahrir.io.net.TrNetworkInterface.TrSentListener sentListener, final double priority) {
			this.addr = addr;
			data = encryptedMessage;
			this.sentListener = sentListener;
			this.priority = priority;

		}

		public int compareTo(final QueuedPacket other) {
			return Double.compare(priority, other.priority);
		}

	}

	@Override
	public void unregisterListenerForSender(final UdpRemoteAddress sender) {
		listenersByAddress.remove(sender);
	}

	@Override
	public void shutdown() {
		sender.active = false;
		sender.interrupt();
		receiver.active = false;
	}

	@Override
	public String toString() {
		return "<" + config.listenPort + ">";
	}

	@Override
	public TrRemoteConnection<UdpRemoteAddress> connect(final UdpRemoteAddress remoteAddress,
			final RSAPublicKey remotePubKey,
			final tahrir.io.net.TrNetworkInterface.TrMessageListener<UdpRemoteAddress> listener,
			final Runnable connectedCallback, final Runnable disconnectedCallback, final boolean unilateral) {
		return new UdpRemoteConnection(this, remoteAddress, remotePubKey, listener, connectedCallback,
				disconnectedCallback, unilateral);
	}
}
