/*
 * Copyright (c) 2008-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AbstractClientSession;

/**
 * <p>A {@link LocalSession} implementation.</p>
 * <p>This {@link LocalSession} implementation communicates with its
 * {@link ServerSession} counterpart without any serialization.</p>
 */
public class LocalSessionImpl extends AbstractClientSession implements LocalSession
{
    private final Queue<ServerMessage.Mutable> _queue = new ConcurrentLinkedQueue<>();
    private final BayeuxServerImpl _bayeux;
    private final String _idHint;
    private ServerSessionImpl _session;
    private String _sessionId;

    protected LocalSessionImpl(BayeuxServerImpl bayeux, String idHint)
    {
        _bayeux = bayeux;
        _idHint = idHint;
    }

    @Override
    public void receive(Message.Mutable message)
    {
        super.receive(message);
        if (Channel.META_DISCONNECT.equals(message.getChannel()) && message.isSuccessful())
            _session = null;
    }

    @Override
    protected void notifyListeners(Message.Mutable message)
    {
        ClientSessionChannel.MessageListener callback = (ClientSessionChannel.MessageListener)message.remove(CALLBACK_KEY);
        if (message.isMeta() || message.isPublishReply())
        {
            String messageId = message.getId();
            callback = messageId == null ? callback : unregisterCallback(messageId);
            if (callback != null)
                notifyListener(callback, message);
        }
        super.notifyListeners(message);
    }

    @Override
    protected AbstractSessionChannel newChannel(ChannelId channelId)
    {
        return new LocalChannel(channelId);
    }

    @Override
    protected ChannelId newChannelId(String channelId)
    {
        return _bayeux.newChannelId(channelId);
    }

    @Override
    protected void sendBatch()
    {
        int size = _queue.size();
        while (size-- > 0)
        {
            ServerMessage.Mutable message = _queue.poll();
            doSend(_session, message);
        }
    }

    public ServerSession getServerSession()
    {
        if (_session == null)
            throw new IllegalStateException("Method handshake() not invoked for local session " + this);
        return _session;
    }

    public void handshake()
    {
        handshake(null);
    }

    public void handshake(Map<String, Object> template)
    {
        handshake(template, null);
    }

    public void handshake(Map<String, Object> template, ClientSessionChannel.MessageListener callback)
    {
        if (_session != null)
            throw new IllegalStateException();

        ServerSessionImpl session = new ServerSessionImpl(_bayeux, this, _idHint);

        ServerMessage.Mutable message = _bayeux.newMessage();
        if (template != null)
            message.putAll(template);
        message.setChannel(Channel.META_HANDSHAKE);

        if (callback != null)
            message.put(CALLBACK_KEY, callback);

        doSend(session, message);

        ServerMessage reply = message.getAssociated();
        if (reply != null && reply.isSuccessful())
        {
            message = _bayeux.newMessage();
            message.setChannel(Channel.META_CONNECT);
            message.getAdvice(true).put(Message.INTERVAL_FIELD, -1L);
            message.setClientId(session.getId());

            doSend(session, message);

            reply = message.getAssociated();
            if (reply != null && reply.isSuccessful())
            {
                _session = session;
                _sessionId = session.getId();
            }
        }
    }

    public void disconnect()
    {
        disconnect(null);
    }

    public void disconnect(ClientSessionChannel.MessageListener callback)
    {
        if (_session != null)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_DISCONNECT);
            message.setClientId(_session.getId());
            if (callback != null)
                message.put(CALLBACK_KEY, callback);
            send(_session, message);
            while (isBatching())
                endBatch();
        }
    }

    public String getId()
    {
        if (_sessionId == null)
            throw new IllegalStateException("Method handshake() not invoked for local session " + this);
        return _sessionId;
    }

    public boolean isConnected()
    {
        return _session != null && _session.isConnected();
    }

    public boolean isHandshook()
    {
        return _session != null && _session.isHandshook();
    }

    @Override
    public String toString()
    {
        return "L:" + (_sessionId == null ? _idHint + "_<disconnected>" : _sessionId);
    }

    /**
     * <p>Enqueues or sends a message to the server.</p>
     * <p>This method will either enqueue the message, if this session {@link #isBatching() is batching},
     * or perform the actual send by calling {@link #doSend(ServerSessionImpl, ServerMessage.Mutable)}.</p>
     *
     * @param session The ServerSession to send as. This normally the current server session, but during handshake it is a proposed server session.
     * @param message The message to send.
     */
    protected void send(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        if (isBatching())
            _queue.add(message);
        else
            doSend(session, message);
    }

    /**
     * <p>Sends a message to the server.</p>
     *
     * @param from    The ServerSession to send as. This normally the current server session, but during handshake it is a proposed server session.
     * @param message The message to send.
     */
    protected void doSend(ServerSessionImpl from, ServerMessage.Mutable message)
    {
        String messageId = newMessageId();
        message.setId(messageId);

        // Remove the synthetic fields before calling the extensions
        ClientSessionChannel.MessageListener subscriber = (ClientSessionChannel.MessageListener)message.remove(SUBSCRIBER_KEY);
        ClientSessionChannel.MessageListener callback = (ClientSessionChannel.MessageListener)message.remove(CALLBACK_KEY);

        if (!extendSend(message))
            return;

        ServerMessage.Mutable reply = _bayeux.handle(from, message);
        if (reply != null)
        {
            reply = _bayeux.extendReply(from, _session, reply);
            if (reply != null)
            {
                registerSubscriber(messageId, subscriber);
                registerCallback(messageId, callback);
                receive(reply);
            }
        }
    }

    /**
     * <p>A channel scoped to this local session.</p>
     */
    protected class LocalChannel extends AbstractSessionChannel
    {
        protected LocalChannel(ChannelId id)
        {
            super(id);
        }

        public ClientSession getSession()
        {
            throwIfReleased();
            return LocalSessionImpl.this;
        }

        public void publish(Object data, MessageListener callback)
        {
            throwIfReleased();
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(getId());
            message.setData(data);
            message.setClientId(LocalSessionImpl.this.getId());
            if (callback != null)
                message.put(CALLBACK_KEY, callback);
            send(_session, message);
        }

        @Override
        protected void sendSubscribe(MessageListener listener, MessageListener callback)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            message.setClientId(LocalSessionImpl.this.getId());
            if (listener != null)
                message.put(SUBSCRIBER_KEY, listener);
            if (callback != null)
                message.put(CALLBACK_KEY, callback);
            send(_session, message);
        }

        @Override
        protected void sendUnSubscribe(MessageListener callback)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            message.setClientId(LocalSessionImpl.this.getId());
            if (callback != null)
                message.put(CALLBACK_KEY, callback);
            send(_session, message);
        }

        @Override
        public String toString()
        {
            return super.toString() + "@" + LocalSessionImpl.this.toString();
        }
    }
}
