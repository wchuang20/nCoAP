package de.uzl.itm.ncoap.communication.observing;

import com.google.common.collect.HashBasedTable;
import de.uzl.itm.ncoap.application.server.webresource.ObservableWebresource;
import de.uzl.itm.ncoap.application.server.webresource.WrappedResourceStatus;
import de.uzl.itm.ncoap.communication.AbstractCoapChannelHandler;
import de.uzl.itm.ncoap.communication.dispatching.client.Token;
import de.uzl.itm.ncoap.communication.events.ObservableWebresourceRegistrationEvent;
import de.uzl.itm.ncoap.communication.events.ObserverAcceptedEvent;
import de.uzl.itm.ncoap.communication.events.ResetReceivedEvent;
import de.uzl.itm.ncoap.message.*;
import de.uzl.itm.ncoap.message.options.ContentFormat;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by olli on 04.09.15.
 */
public class ServerObservationHandler extends AbstractCoapChannelHandler implements Observer, ResetReceivedEvent.Handler,
        ObserverAcceptedEvent.Handler, ObservableWebresourceRegistrationEvent.Handler {

    private static Logger LOG = LoggerFactory.getLogger(ServerObservationHandler.class.getName());

    private HashBasedTable<InetSocketAddress, Token, ObservableWebresource> observations1;
    private HashBasedTable<ObservableWebresource, InetSocketAddress, Token> observations2;
    private HashBasedTable<InetSocketAddress, Token, Long> contentFormats;

    private ReentrantReadWriteLock lock;
    private ScheduledExecutorService executor;
    private ChannelHandlerContext ctx;


    public ServerObservationHandler(ScheduledExecutorService executor){
        this.executor = executor;
        this.observations1 = HashBasedTable.create();
        this.observations2 = HashBasedTable.create();
        this.contentFormats = HashBasedTable.create();

        this.lock = new ReentrantReadWriteLock();
    }


    @Override
    public boolean handleInboundCoapMessage(ChannelHandlerContext ctx, CoapMessage coapMessage,
            InetSocketAddress remoteSocket) {

        if(coapMessage instanceof CoapRequest) {
            stopObservation(remoteSocket, coapMessage.getToken());
        }

        return true;
    }


    @Override
    public boolean handleOutboundCoapMessage(ChannelHandlerContext ctx, CoapMessage coapMessage,
            InetSocketAddress remoteSocket) {

        if(coapMessage instanceof CoapResponse && !((CoapResponse) coapMessage).isUpdateNotification()){
            stopObservation(remoteSocket, coapMessage.getToken());
        }

        return true;
    }


    @Override
    public void handleResetReceivedEvent(ResetReceivedEvent event) {
        stopObservation(event.getRemoteEndpoint(), event.getToken());
    }


    @Override
    public void handleObserverAcceptedEvent(ObserverAcceptedEvent event) {
        startObservation(event.getRemoteEndpoint(), event.getToken(), event.getWebresource(), event.getContentFormat());
    }


    @Override
    public void handleObservableWebresourceRegistrationEvent(ObservableWebresourceRegistrationEvent event) {
        ObservableWebresource webresource = event.getWebresource();
        webresource.addObserver(this);
    }

    public void setChannelHandlerContext(ChannelHandlerContext ctx){
        this.ctx = ctx;
    }

    private void startObservation(InetSocketAddress remoteAddress, Token token, ObservableWebresource webresource,
            long contentFormat){

        try {
            this.lock.writeLock().lock();
            this.observations1.put(remoteAddress, token, webresource);
            this.observations2.put(webresource, remoteAddress, token);
            this.contentFormats.put(remoteAddress, token, contentFormat);
            LOG.info("Client \"{}\" is now observing \"{}\".", remoteAddress, webresource.getUriPath());

        } finally {
            this.lock.writeLock().unlock();
        }
    }


    private void stopObservation(InetSocketAddress remoteSocket, Token token){
        try {
            this.lock.readLock().lock();
            if(!this.observations1.contains(remoteSocket, token)){
                return;
            }
        } finally {
            this.lock.readLock().unlock();
        }

        try {
            this.lock.writeLock().lock();
            ObservableWebresource webresource = this.observations1.remove(remoteSocket, token);
            if(webresource == null){
                return;
            }
            this.observations2.remove(webresource, remoteSocket);
            this.contentFormats.remove(remoteSocket, token);
            LOG.info("Client \"{}\" is no longer observing \"{}\" (token was: \"{}\").",
                    new Object[]{remoteSocket, webresource.getUriPath(), token});

        } finally {
            this.lock.writeLock().unlock();
        }
    }


    @Override
    public void update(Observable observable, Object type) {
        ObservableWebresource webresource = (ObservableWebresource) observable;
        if(type.equals(ObservableWebresource.UPDATE)) {
            sendUpdateNotifications(webresource);
        } else if(type.equals(ObservableWebresource.SHUTDOWN)){
            sendShutdownNotifications(webresource);
        }
    }

    private void sendShutdownNotifications(ObservableWebresource webresource){
        try {
            this.lock.writeLock().lock();
            Map<InetSocketAddress, Token> observations = new HashMap<>(this.observations2.row(webresource));

            for (Map.Entry<InetSocketAddress, Token> observation : observations.entrySet()) {
                InetSocketAddress remoteSocket = observation.getKey();
                Token token = observation.getValue();
                stopObservation(remoteSocket, token);

                this.executor.submit(new ShutdownNotificationTask(remoteSocket, token, webresource.getUriPath()));
            }
        } finally {
            this.lock.writeLock().unlock();
        }

    }


    private void sendUpdateNotifications(ObservableWebresource webresource){
        try {
            this.lock.readLock().lock();
            Map<Long, WrappedResourceStatus> representations = new HashMap<>();
            for(Map.Entry<InetSocketAddress, Token> observation : this.observations2.row(webresource).entrySet()){
                InetSocketAddress remoteSocket = observation.getKey();
                Token token = observation.getValue();
                long contentFormat = this.contentFormats.get(remoteSocket, token);

                WrappedResourceStatus representation = representations.get(contentFormat);
                if(representation == null) {
                    representation = webresource.getWrappedResourceStatus(contentFormat);
                    representations.put(contentFormat, representation);
                }

                // schedule update notification (immediately)
                boolean confirmable = webresource.isUpdateNotificationConfirmable(remoteSocket);
                MessageType.Name messageType =  confirmable ? MessageType.Name.CON : MessageType.Name.NON;
                this.executor.submit(new UpdateNotificationTask(remoteSocket, representation, messageType, token));
            }
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private class ShutdownNotificationTask implements Runnable{

        private InetSocketAddress remoteSocket;
        private Token token;
        private String webresourcePath;


        public ShutdownNotificationTask(InetSocketAddress remoteSocket, Token token, String webresourcePath){
            this.remoteSocket = remoteSocket;
            this.token = token;
            this.webresourcePath = webresourcePath;
        }

        public void run() {
            CoapResponse coapResponse = new CoapResponse(MessageType.Name.NON, MessageCode.Name.NOT_FOUND_404);
            coapResponse.setToken(token);
            String content = "Resource \"" + this.webresourcePath + "\" is no longer available.";
            coapResponse.setContent(content.getBytes(CoapMessage.CHARSET), ContentFormat.TEXT_PLAIN_UTF8);

            ChannelFuture future = Channels.future(ctx.getChannel());
            Channels.write(ctx, future, coapResponse, remoteSocket);

            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if(!future.isSuccess()){
                        LOG.error("Shutdown Notification Failure!", future.getCause());
                    }
                    else{
                        LOG.info("Sent NOT_FOUND to \"{}\" (Token: {}).", remoteSocket, token);
                    }
                }
            });
        }
    }

    private class UpdateNotificationTask implements Runnable{

        private InetSocketAddress remoteSocket;
        private MessageType.Name messageType;
        private Token token;
        private WrappedResourceStatus representation;

        public UpdateNotificationTask(InetSocketAddress remoteSocket, WrappedResourceStatus representation,
                    MessageType.Name messageType, Token token){

            this.remoteSocket = remoteSocket;
            this.representation = representation;
            this.messageType = messageType;
            this.token = token;
        }

        public void run() {
            CoapResponse updateNotification = new CoapResponse(messageType, MessageCode.Name.CONTENT_205);
            updateNotification.setToken(token);
            updateNotification.setEtag(representation.getEtag());
            updateNotification.setContent(representation.getContent(), representation.getContentFormat());
            updateNotification.setObserve();

            ChannelFuture future = Channels.future(ctx.getChannel());
            Channels.write(ctx, future, updateNotification, remoteSocket);

            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if(!future.isSuccess()){
                        LOG.error("Update Notification Failure!", future.getCause());
                    }
                    else{
                        LOG.info("Sent Update Notification to \"{}\" (Token: {}).", remoteSocket, token);
                    }
                }
            });
        }
    }
}
