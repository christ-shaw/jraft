package net.data.technology.jraft.extensions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import net.data.technology.jraft.RaftMessageHandler;
import net.data.technology.jraft.RaftRequestMessage;
import net.data.technology.jraft.RaftResponseMessage;
import net.data.technology.jraft.RpcListener;

public class RpcTcpListener implements RpcListener {
	private int port;
	private Logger logger;
	private AsynchronousServerSocketChannel listener;
	
	public RpcTcpListener(int port){
		this.port = port;
		this.logger = LogManager.getLogger(getClass());
	}

	@Override
	public void startListening(RaftMessageHandler messageHandler) {
		int processors = Runtime.getRuntime().availableProcessors();
		ExecutorService executorService = Executors.newFixedThreadPool(processors);
		try{
			AsynchronousChannelGroup channelGroup = AsynchronousChannelGroup.withThreadPool(executorService);
			this.listener = AsynchronousServerSocketChannel.open(channelGroup);
			this.listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			this.listener.bind(new InetSocketAddress(this.port));
			this.acceptRequests(messageHandler);
		}catch(IOException exception){
			logger.error("failed to start the listener due to io error", exception);
		}
	}
	
	private void acceptRequests(RaftMessageHandler messageHandler){
		try{
			this.listener.accept(messageHandler, AsyncUtility.handlerFrom(
					(AsynchronousSocketChannel connection, RaftMessageHandler handler) -> {
						acceptRequests(handler);
						readRequest(connection, handler);
					}, 
					(Throwable error, RaftMessageHandler handler) -> {
						logger.error("accepting a new connection failed, will still keep accepting more requests", error);
						acceptRequests(handler);
					}));
		}catch(Exception exception){
			logger.error("failed to accept new requests, will retry", exception);
			this.acceptRequests(messageHandler);
		}
	}
	
	private void readRequest(final AsynchronousSocketChannel connection, RaftMessageHandler messageHandler){
		ByteBuffer buffer = ByteBuffer.allocate(BinaryUtils.RAFT_REQUEST_HEADER_SIZE);
		try{
			connection.read(buffer, messageHandler, handlerFrom((Integer bytesRead, final RaftMessageHandler handler) -> {
				if(bytesRead.intValue() < BinaryUtils.RAFT_REQUEST_HEADER_SIZE){
					logger.info("failed to read the request header from client socket");
					closeSocket(connection);
				}else{
					try{
						final Pair<RaftRequestMessage, Integer> requestInfo = BinaryUtils.bytesToRequestMessage(buffer.array());
						if(requestInfo.getSecond().intValue() > 0){
							ByteBuffer logBuffer = ByteBuffer.allocate(requestInfo.getSecond().intValue());
							connection.read(logBuffer, null, handlerFrom((Integer size, Object attachment) -> {
								if(size.intValue() < requestInfo.getSecond().intValue()){
									logger.info("failed to read the log entries data from client socket");
									closeSocket(connection);
								}else{
									try{
										requestInfo.getFirst().setLogEntries(BinaryUtils.bytesToLogEntries(logBuffer.array()));
										processRequest(connection, requestInfo.getFirst(), handler);
									}catch(Throwable error){
										logger.info("log entries parsing error", error);
										closeSocket(connection);
									}
								}
							}, connection));
						}else{
							processRequest(connection, requestInfo.getFirst(), handler);
						}
					}catch(Throwable runtimeError){
						// if there are any conversion errors, we need to close the client socket to prevent more errors
						closeSocket(connection);
						logger.info("message reading/parsing error", runtimeError);
					}
				}
			}, connection));
		}catch(Exception readError){
			logger.info("failed to read more request from client socket", readError);
			closeSocket(connection);
		}
	}
	
	private void processRequest(AsynchronousSocketChannel connection, RaftRequestMessage request, RaftMessageHandler messageHandler){
		try{
			RaftResponseMessage response = messageHandler.processRequest(request);
			final ByteBuffer buffer = ByteBuffer.wrap(BinaryUtils.messageToBytes(response));
			connection.write(buffer, null, handlerFrom((Integer bytesSent, Object attachment) -> {
				if(bytesSent.intValue() < buffer.limit()){
					logger.info("failed to completely send the response.");
					closeSocket(connection);
				}else{
					logger.debug("response message sent.");
					if(connection.isOpen()){
						readRequest(connection, messageHandler);
					}
				}
			}, connection));
		}catch(Throwable error){
			// for any errors, we will close the socket to prevent more errors
			closeSocket(connection);
			logger.error("failed to process the request or send the response", error);
		}
	}

	private static <V, A> CompletionHandler<V, A> handlerFrom(BiConsumer<V, A> completed, AsynchronousSocketChannel connection) {
	    return AsyncUtility.handlerFrom(completed, (Throwable error, A attachment) -> {
	                    LogManager.getLogger(RpcTcpListener.class).info("socket server failure", error);
	                    if(connection != null && connection.isOpen()){
	                    	closeSocket(connection);
	                    }
	                });
	}
	
	private static void closeSocket(AsynchronousSocketChannel connection){
		try{
    		connection.close();
    	}catch(IOException ex){
    		LogManager.getLogger(RpcTcpListener.class).info("failed to close client socket", ex);
    	}
	}
}