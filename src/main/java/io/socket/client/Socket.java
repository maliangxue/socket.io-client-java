package io.socket.client;

import io.socket.emitter.Emitter;
import io.socket.hasbinary.HasBinary;
import io.socket.parser.Packet;
import io.socket.parser.Parser;
import io.socket.thread.EventThread;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The socket class for Socket.IO Client.
 */
public class Socket extends Emitter {

    private static final Logger logger = Logger.getLogger(Socket.class.getName());

    /**
     * Called on a connection.
     */
    public static final String EVENT_CONNECT = "connect";

    public static final String EVENT_CONNECTING = "connecting";

    /**
     * Called on a disconnection.
     */
    public static final String EVENT_DISCONNECT = "disconnect";

    /**
     * Called on a connection error.
     *
     * <p>Parameters:</p>
     * <ul>
     *   <li>(Exception) error data.</li>
     * </ul>
     */
    public static final String EVENT_ERROR = "error";

    public static final String EVENT_MESSAGE = "message";

    public static final String EVENT_CONNECT_ERROR = Manager.EVENT_CONNECT_ERROR;

    public static final String EVENT_CONNECT_TIMEOUT = Manager.EVENT_CONNECT_TIMEOUT;

    public static final String EVENT_RECONNECT = Manager.EVENT_RECONNECT;

    public static final String EVENT_RECONNECT_ERROR = Manager.EVENT_RECONNECT_ERROR;

    public static final String EVENT_RECONNECT_FAILED = Manager.EVENT_RECONNECT_FAILED;

    public static final String EVENT_RECONNECT_ATTEMPT = Manager.EVENT_RECONNECT_ATTEMPT;

    public static final String EVENT_RECONNECTING = Manager.EVENT_RECONNECTING;

    protected static Map<String, Integer> events = new HashMap<String, Integer>() {{
        put(EVENT_CONNECT, 1);
        put(EVENT_CONNECT_ERROR, 1);
        put(EVENT_CONNECT_TIMEOUT, 1);
        put(EVENT_CONNECTING, 1);
        put(EVENT_DISCONNECT, 1);
        put(EVENT_ERROR, 1);
        put(EVENT_RECONNECT, 1);
        put(EVENT_RECONNECT_ATTEMPT, 1);
        put(EVENT_RECONNECT_FAILED, 1);
        put(EVENT_RECONNECT_ERROR, 1);
        put(EVENT_RECONNECTING, 1);
    }};

    /*package*/ String id;

    private volatile boolean connected;
    private int ids;
    private String nsp;
    private Manager io;
    private Map<Integer, Ack> acks = new HashMap<Integer, Ack>();
    private Queue<On.Handle> subs;
    private final Queue<List<Object>> receiveBuffer = new LinkedList<List<Object>>();
    private final Queue<Packet<JSONArray>> sendBuffer = new LinkedList<Packet<JSONArray>>();

    public Socket(Manager io, String nsp) {
        this.io = io;
        this.nsp = nsp;
    }

    private void subEvents() {
        if (this.subs != null) return;

        final Manager io = Socket.this.io;
        Socket.this.subs = new LinkedList<On.Handle>() {{
            add(On.on(io, Manager.EVENT_OPEN, new Listener() {
                @Override
                public void call(Object... args) {
                    Socket.this.onopen();
                }
            }));
            add(On.on(io, Manager.EVENT_PACKET, new Listener() {
                @Override
                public void call(Object... args) {
                    Socket.this.onpacket((Packet<?>) args[0]);
                }
            }));
            add(On.on(io, Manager.EVENT_CLOSE, new Listener() {
                @Override
                public void call(Object... args) {
                    Socket.this.onclose(args.length > 0 ? (String) args[0] : null);
                }
            }));
        }};
    }

    /**
     * Connects the socket.
     */
    public Socket open() {
        EventThread.exec(new Runnable() {
            @Override
            public void run() {
                if (Socket.this.connected) return;

                Socket.this.subEvents();
                Socket.this.io.open(); // ensure open
                if (Manager.ReadyState.OPEN == Socket.this.io.readyState) Socket.this.onopen();
                Socket.this.emit(EVENT_CONNECTING);
            }
        });
        return this;
    }

    /**
     * Connects the socket.
     */
    public Socket connect() {
        return this.open();
    }

    /**
     * Send messages.
     *
     * @param args data to send.
     * @return a reference to this object.
     */
    public Socket send(final Object... args) {
        EventThread.exec(new Runnable() {
            @Override
            public void run() {
                Socket.this.emit(EVENT_MESSAGE, args);
            }
        });
        return this;
    }

    /**
     * Emits an event. When you pass {@link Ack} at the last argument, then the acknowledge is done.
     *
     * @param event an event name.
     * @param args data to send.
     * @return a reference to this object.
     */
    @Override
    public Emitter emit(final String event, final Object... args) {
        EventThread.exec(new Runnable() {
            @Override
            public void run() {
                if (events.containsKey(event)) {
                    Socket.super.emit(event, args);
                    return;
                }

                List<Object> _args = new ArrayList<Object>(args.length + 1);
                _args.add(event);
                _args.addAll(Arrays.asList(args));

                JSONArray jsonArgs = new JSONArray();
                for (Object arg : _args) {
                    jsonArgs.put(arg);
                }
                int parserType = HasBinary.hasBinary(jsonArgs) ? Parser.BINARY_EVENT : Parser.EVENT;
                Packet<JSONArray> packet = new Packet<JSONArray>(parserType, jsonArgs);

                if (_args.get(_args.size() - 1) instanceof Ack) {
                    logger.fine(String.format("emitting packet with ack id %d", Socket.this.ids));
                    Socket.this.acks.put(Socket.this.ids, (Ack)_args.remove(_args.size() - 1));
                    jsonArgs = remove(jsonArgs, jsonArgs.length() - 1);
                    packet.data = jsonArgs;
                    packet.id = Socket.this.ids++;
                }

                if (Socket.this.connected) {
                    Socket.this.packet(packet);
                } else {
                    Socket.this.sendBuffer.add(packet);
                }
            }
        });
        return this;
    }

    private static JSONArray remove(JSONArray a, int pos) {
        JSONArray na = new JSONArray();
        for (int i = 0; i < a.length(); i++){
            if (i != pos) {
                Object v;
                try {
                    v = a.get(i);
                } catch (JSONException e) {
                    v = null;
                }
                na.put(v);
            }
        }
        return na;
    }

    /**
     * Emits an event with an acknowledge.
     *
     * @param event an event name
     * @param args data to send.
     * @param ack the acknowledgement to be called
     * @return a reference to this object.
     */
    public Emitter emit(final String event, final Object[] args, final Ack ack) {
        EventThread.exec(new Runnable() {
            @Override
            public void run() {
                List<Object> _args = new ArrayList<Object>() {{
                    add(event);
                    if (args != null) {
                        addAll(Arrays.asList(args));
                    }
                }};
                
                JSONArray jsonArgs = new JSONArray();
                for (Object _arg : _args) {
                    jsonArgs.put(_arg);
                }
                int parserType = HasBinary.hasBinary(jsonArgs) ? Parser.BINARY_EVENT : Parser.EVENT;
                Packet<JSONArray> packet = new Packet<JSONArray>(parserType, jsonArgs);

                logger.fine(String.format("emitting packet with ack id %d", ids));
                Socket.this.acks.put(ids, ack);
                packet.id = ids++;

                Socket.this.packet(packet);
            }
        });
        return this;
    }

    private void packet(Packet packet) {
        packet.nsp = this.nsp;
        this.io.packet(packet);
    }

    private void onopen() {
        logger.fine("transport is open - connecting");

        if (!"/".equals(this.nsp)) {
            this.packet(new Packet(Parser.CONNECT));
        }
    }

    private void onclose(String reason) {
        logger.fine(String.format("close (%s)", reason));
        this.connected = false;
        this.id = null;
        this.emit(EVENT_DISCONNECT, reason);
    }

    private void onpacket(Packet<?> packet) {
        if (!this.nsp.equals(packet.nsp)) return;

        switch (packet.type) {
            case Parser.CONNECT:
                this.onconnect();
                break;

            case Parser.EVENT: {
                @SuppressWarnings("unchecked")
                Packet<JSONArray> p = (Packet<JSONArray>) packet;
                this.onevent(p);
                break;
            }

            case Parser.BINARY_EVENT: {
                @SuppressWarnings("unchecked")
                Packet<JSONArray> p = (Packet<JSONArray>) packet;
                this.onevent(p);
                break;
            }

            case Parser.ACK: {
                @SuppressWarnings("unchecked")
                Packet<JSONArray> p = (Packet<JSONArray>) packet;
                this.onack(p);
                break;
            }

            case Parser.BINARY_ACK: {
                @SuppressWarnings("unchecked")
                Packet<JSONArray> p = (Packet<JSONArray>) packet;
                this.onack(p);
                break;
            }

            case Parser.DISCONNECT:
                this.ondisconnect();
                break;

            case Parser.ERROR:
                this.emit(EVENT_ERROR, packet.data);
                break;
        }
    }

    private void onevent(Packet<JSONArray> packet) {
        List<Object> args = new ArrayList<Object>(Arrays.asList(toArray(packet.data)));
        logger.fine(String.format("emitting event %s", args));

        if (packet.id >= 0) {
            logger.fine("attaching ack callback to event");
            args.add(this.ack(packet.id));
        }

        if (this.connected) {
            if (args.isEmpty()) return;
            String event = args.remove(0).toString();
            super.emit(event, args.toArray());
        } else {
            this.receiveBuffer.add(args);
        }
    }

    private Ack ack(final int id) {
        final Socket self = this;
        final boolean[] sent = new boolean[] {false};
        return new Ack() {
            @Override
            public void call(final Object... args) {
                EventThread.exec(new Runnable() {
                    @Override
                    public void run() {
                        if (sent[0]) return;
                        sent[0] = true;
                        logger.fine(String.format("sending ack %s", args.length != 0 ? args : null));

                        JSONArray jsonArgs = new JSONArray();
                        for (Object arg : args) {
                            jsonArgs.put(arg);
                        }

                        int type = HasBinary.hasBinary(jsonArgs)
                            ? Parser.BINARY_ACK : Parser.ACK;
                        Packet<JSONArray> packet = new Packet<JSONArray>(type, jsonArgs);
                        packet.id = id;
                        self.packet(packet);
                    }
                });
            }
        };
    }

    private void onack(Packet<JSONArray> packet) {
        Ack fn = this.acks.remove(packet.id);
        if (fn != null) {
            logger.fine(String.format("calling ack %s with %s", packet.id, packet.data));
            fn.call(toArray(packet.data));
        } else {
            logger.fine(String.format("bad ack %s", packet.id));
        }
    }

    private void onconnect() {
        this.connected = true;
        this.emit(EVENT_CONNECT);
        this.emitBuffered();
    }

    private void emitBuffered() {
        List<Object> data;
        while ((data = this.receiveBuffer.poll()) != null) {
            String event = (String)data.get(0);
            super.emit(event, data.toArray());
        }
        this.receiveBuffer.clear();

        Packet<JSONArray> packet;
        while ((packet = this.sendBuffer.poll()) != null) {
            this.packet(packet);
        }
        this.sendBuffer.clear();
    }

    private void ondisconnect() {
        logger.fine(String.format("server disconnect (%s)", this.nsp));
        this.destroy();
        this.onclose("io server disconnect");
    }

    private void destroy() {
        if (this.subs != null) {
            // clean subscriptions to avoid reconnection
            for (On.Handle sub : this.subs) {
                sub.destroy();
            }
            this.subs = null;
        }

        this.io.destroy(this);
    }

    /**
     * Disconnects the socket.
     *
     * @return a reference to this object.
     */
    public Socket close() {
        EventThread.exec(new Runnable() {
            @Override
            public void run() {
                if (Socket.this.connected) {
                    logger.fine(String.format("performing disconnect (%s)", Socket.this.nsp));
                    Socket.this.packet(new Packet(Parser.DISCONNECT));
                }

                Socket.this.destroy();

                if (Socket.this.connected) {
                    Socket.this.onclose("io client disconnect");
                }
            }
        });
        return this;
    }

    /**
     * Disconnects the socket.
     *
     * @return a reference to this object.
     */
    public Socket disconnect() {
        return this.close();
    }

    public Manager io() {
        return this.io;
    }

    public boolean connected() {
        return this.connected;
    }

    /**
     * A property on the socket instance that is equal to the underlying engine.io socket id.
     *
     * The value is present once the socket has connected, is removed when the socket disconnects and is updated if the socket reconnects.
     *
     * @return a socket id
     */
    public String id() {
        return this.id;
    }

    private static Object[] toArray(JSONArray array) {
        int length = array.length();
        Object[] data = new Object[length];
        for (int i = 0; i < length; i++) {
            Object v;
            try {
                v = array.get(i);
            } catch (JSONException e) {
                logger.log(Level.WARNING, "An error occured while retrieving data from JSONArray", e);
                v = null;
            }
            data[i] = JSONObject.NULL.equals(v) ? null : v;
        }
        return data;
    }
}

