package peno.htttp.impl;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.rabbitmq.client.Channel;

public class RequestProvider {

	private final AtomicInteger counter = new AtomicInteger();
	private final String queue;

	public RequestProvider(Channel channel) throws IOException {
		queue = channel.queueDeclare().getQueue();
	}

	public int nextRequestId() {
		return counter.incrementAndGet();
	}

	public String getQueue() {
		return queue;
	}

}
