package com.flumeng.plugins.source;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.flume.ChannelException;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDrivenSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.AbstractSource;
import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.jboss.netty.channel.socket.DatagramChannelFactory;
import org.jboss.netty.channel.socket.nio.NioDatagramChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UDPSource extends AbstractSource implements EventDrivenSource, Configurable {
  private String    host;
  private int       port;
  private char      delimiter;
  private int       maxSize;
  private ConnectionlessBootstrap  bootstrap;
  private DatagramChannel channel;
  private static final Logger logger = LoggerFactory.getLogger(UDPSource.class);

  @Override
  public void configure(Context context) {
    host = context.getString("host","0.0.0.0");
    port = context.getInteger("port");
    delimiter = context.getString("delimiter","\n").charAt(0);
    maxSize = context.getInteger("maxSize",65536);
  }

  @Override
  public void start() {
	  DatagramChannelFactory datagramChannelFactory = new NioDatagramChannelFactory(Executors.newCachedThreadPool());
	  bootstrap=new ConnectionlessBootstrap(datagramChannelFactory);
	  final UDPChannelHandler handler = new UDPChannelHandler(maxSize, delimiter);
      bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
          @Override
          public ChannelPipeline getPipeline() {
            return Channels.pipeline(handler);
          }
      });
      channel = (DatagramChannel)bootstrap.bind(new InetSocketAddress(host, port));
      super.start();
  }

  @Override
  public void stop() {
	  if (channel != null) {
		  channel.close();
	      try {
	    	  channel.getCloseFuture().await(60, TimeUnit.SECONDS);
	      } catch (InterruptedException e) {
	    	  logger.warn("netty server stop interrupted", e);
	      } finally {
	    	  channel = null;
	      }
	  }
	  super.stop();
  }

 
  public class UDPChannelHandler extends SimpleChannelHandler {
    protected int maxSize;
    protected char delimiter;
    protected ByteArrayOutputStream eventByteContainer;

    public UDPChannelHandler(int maxSize, char delimiter) {
      super();
      this.maxSize = maxSize;
      this.delimiter = delimiter;
      this.eventByteContainer = new ByteArrayOutputStream(this.maxSize);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent event) {
    	try {
	        Event ev = extractEvent((ChannelBuffer)event.getMessage());
	        if (ev == null) {
	        	return;
	        }
        	getChannelProcessor().processEvent(ev);
    	} catch (ChannelException ex) {
    		logger.error("Error occured when writting to channel", ex);
    		return;
    	}
    }

    private Event extractEvent(ChannelBuffer in) {
      byte    b = 0;
      Event   event = null;
      boolean meetDelimiter = false;
      while (in.readable()) {
          b = in.readByte();
          if (!meetDelimiter && b == delimiter) {
        	  byte[] body = eventByteContainer.toByteArray();
              event = EventBuilder.withBody(body);
              eventByteContainer.reset();
        	  meetDelimiter = true;
          }else {
        	  eventByteContainer.write(b);
          }
          
          if(eventByteContainer.size() == this.maxSize){
        	   if(!meetDelimiter){
	        	   logger.warn("Event larger than specified size,container capicity:{}, config maxSize: {}.", eventByteContainer.size(),this.maxSize);
	        	   eventByteContainer.reset();
	        	   break;
        	   }else{
        		   logger.warn("More than one events received once, except 1st event, the other events capicity is larger than specified size,{}",this.maxSize);
        		   break;
        	   }
          }
     }
     return event;
    }


  }
  
}
