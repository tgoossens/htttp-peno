package peno.htttp.impl;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;

public abstract class Requester extends Consumer {

	private final RequestProvider provider;

	private String requestId;
	private ScheduledFuture<?> timeoutFuture;

	public Requester(Channel channel, RequestProvider provider) throws IOException {
		super(channel);
		this.provider = provider;
	}

	protected void request(String exchange, String topic, byte[] message) throws IOException {
		request(exchange, topic, message, -1);
	}

	protected void request(String exchange, String topic, byte[] message, int timeout) throws IOException {
		// Cancel any running requests
		cancelRequest();

		// Create request
		requestId = "" + provider.nextRequestId();
		AMQP.BasicProperties props = new AMQP.BasicProperties().builder().timestamp(new Date())
				.contentType("text/plain").deliveryMode(1).expiration(timeout + "").correlationId(requestId)
				.replyTo(getQueue()).build();

		// Publish
		getChannel().basicPublish(exchange, topic, props, message);

		// Set timeout
		if (timeout > 0) {
			timeoutFuture = provider.scheduleTimeout(new Runnable() {
				@Override
				public void run() {
					handleTimeout();
				}
			}, timeout);
		}
	}

	protected void cancelRequest() {
		// Cancel timeout
		if (timeoutFuture != null) {
			timeoutFuture.cancel(false);
			timeoutFuture = null;
		}
	}

	@Override
	public void handleMessage(String topic, Map<String, Object> message, BasicProperties props) {
		if (props.getCorrelationId().equals(requestId)) {
			handleResponse(message, props);
		}
	}

	protected abstract void handleResponse(Map<String, Object> message, BasicProperties props);

	protected abstract void handleTimeout();

}
