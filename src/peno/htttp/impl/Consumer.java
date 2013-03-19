package peno.htttp.impl;

import java.io.IOException;
import java.util.Map;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.tools.json.JSONReader;

public abstract class Consumer extends DefaultConsumer {

	private final String queue;

	public Consumer(Channel channel, String queue) throws IOException {
		super(channel);
		this.queue = queue;

		// Consume queue
		channel.basicConsume(queue, true, this);
	}

	public Consumer(Channel channel) throws IOException {
		this(channel, channel.queueDeclare().getQueue());
	}

	protected String getQueue() {
		return queue;
	}

	public void bind(String exchange, String routingKey) throws IOException {
		getChannel().queueBind(getQueue(), exchange, routingKey);
	}

	public void unbind(String exchange, String routingKey) throws IOException {
		getChannel().queueUnbind(getQueue(), exchange, routingKey);
	}

	public void terminate() {
		try {
			getChannel().queueDelete(getQueue());
		} catch (IOException e) {
		} catch (ShutdownSignalException e) {
		}
	}

	@Override
	public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties props, byte[] body)
			throws IOException {
		String topic = envelope.getRoutingKey();
		Map<String, Object> message = parseMessage(body);

		handleMessage(topic, message, props);
	}

	public abstract void handleMessage(String topic, Map<String, Object> message, BasicProperties props)
			throws IOException;

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseMessage(byte[] body) {
		return (Map<String, Object>) new JSONReader().read(new String(body));
	}

}
