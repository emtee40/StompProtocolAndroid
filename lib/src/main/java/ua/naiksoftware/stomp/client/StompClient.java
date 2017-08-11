package ua.naiksoftware.stomp.client;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import java8.util.StringJoiner;
import java8.util.concurrent.CompletableFuture;
import rx.Completable;
import rx.Observable;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import ua.naiksoftware.stomp.ConnectionProvider;
import ua.naiksoftware.stomp.LifecycleEvent;
import ua.naiksoftware.stomp.StompHeader;

/**
 * Created by naik on 05.05.16.
 */
public class StompClient {

    private static final String TAG = StompClient.class.getSimpleName();

    public static final String SUPPORTED_VERSIONS = "1.1,1.0";
    public static final String DEFAULT_ACK = "auto";

    private final String tag = StompClient.class.getSimpleName();
    private final ConnectionProvider mConnectionProvider;
    private HashMap<String, String> mTopics;
    private boolean mConnected;
    private boolean isConnecting;

    private PublishSubject<StompMessage> mMessageStream;
    private CompletableFuture<Boolean> mConnectionFuture;
    private Completable mConnectionComplete;
    private HashMap<String, Observable<StompMessage>> mStreamMap;
    private Parser parser;

    public StompClient(ConnectionProvider connectionProvider) {
        mConnectionProvider = connectionProvider;
        mMessageStream = PublishSubject.create();
        mStreamMap = new HashMap<>();
        resetStatus();
        parser = Parser.NONE;
    }

    private void resetStatus() {
        mConnectionFuture = new CompletableFuture<>();
        mConnectionComplete = Completable.fromFuture(mConnectionFuture).subscribeOn(Schedulers.newThread());
    }

    public enum Parser {
        NONE,
        RABBITMQ
    }

    /**
     * Set the wildcard parser for Topic subscription.
     * <p>
     * Right now, the only options are NONE and RABBITMQ.
     * <p>
     * When set to RABBITMQ, topic subscription allows for RMQ-style wildcards.
     * <p>
     * See more info <a href="https://www.rabbitmq.com/tutorials/tutorial-five-java.html">here</a>.
     *
     * @param parser Set to NONE by default
     */
    public void setParser(Parser parser) {
        this.parser = parser;
    }

    /**
     * Connect without reconnect if connected
     */
    public void connect() {
        connect(null);
    }

    public void connect(boolean reconnect) {
        connect(null, reconnect);
    }

    /**
     * Connect without reconnect if connected
     *
     * @param _headers HTTP headers to send in the INITIAL REQUEST, i.e. during the protocol upgrade
     */
    public void connect(List<StompHeader> _headers) {
        connect(_headers, false);
    }

    /**
     * If already connected and reconnect=false - nope
     *
     * @param _headers HTTP headers to send in the INITIAL REQUEST, i.e. during the protocol upgrade
     */
    public void connect(@Nullable List<StompHeader> _headers, boolean reconnect) {
        if (reconnect) disconnect();
        if (mConnected) return;
        lifecycle()
                .subscribe(lifecycleEvent -> {
                    switch (lifecycleEvent.getType()) {
                        case OPENED:
                            List<StompHeader> headers = new ArrayList<>();
                            headers.add(new StompHeader(StompHeader.VERSION, SUPPORTED_VERSIONS));
                            if (_headers != null) headers.addAll(_headers);
                            mConnectionProvider.send(new StompMessage(StompCommand.CONNECT, headers, null).compile())
                                    .subscribe();
                            break;

                        case CLOSED:
                            mConnected = false;
                            isConnecting = false;
                            break;

                        case ERROR:
                            mConnected = false;
                            isConnecting = false;
                            break;
                    }
                });

        isConnecting = true;
        mConnectionProvider.messages()
                .map(StompMessage::from)
                .doOnNext(this::callSubscribers)
                .filter(msg -> msg.getStompCommand().equals(StompCommand.CONNECTED))
                .subscribe(stompMessage -> {
                    mConnected = true;
                    isConnecting = false;
                    mConnectionFuture.complete(true);
                });
    }

    public Completable send(String destination) {
        return send(destination, null);
    }

    public Completable send(String destination, String data) {
        return send(new StompMessage(
                StompCommand.SEND,
                Collections.singletonList(new StompHeader(StompHeader.DESTINATION, destination)),
                data));
    }

    public Completable send(@NonNull StompMessage stompMessage) {
        Completable completable = mConnectionProvider.send(stompMessage.compile());
        return completable.startWith(mConnectionComplete);
    }

    private void callSubscribers(StompMessage stompMessage) {
        mMessageStream.onNext(stompMessage);
    }

    public Observable<LifecycleEvent> lifecycle() {
        return mConnectionProvider.getLifecycleReceiver();
    }

    public void disconnect() {
        resetStatus();
        mConnectionProvider.disconnect().subscribe(() -> mConnected = false);
    }

    public Observable<StompMessage> topic(String destinationPath) {
        return topic(destinationPath, null);
    }

    public Observable<StompMessage> topic(@NonNull String destPath, List<StompHeader> headerList) {
        if (destPath == null)
            return Observable.error(new IllegalArgumentException("Topic path cannot be null"));
        else if (!mStreamMap.containsKey(destPath))
            mStreamMap.put(destPath,
                    mMessageStream
                            .filter(msg -> matches(destPath, msg))
                            .doOnSubscribe(() -> subscribePath(destPath, headerList).subscribe())
                            .doOnUnsubscribe(() -> unsubscribePath(destPath).subscribe())
                            .share()
            );
        return mStreamMap.get(destPath);
    }

    private boolean matches(String path, StompMessage msg) {
        String dest = msg.findHeader(StompHeader.DESTINATION);
        if (dest == null) return false;
        boolean ret;

        switch (parser) {
            case NONE:
                ret = path.equals(dest);
                break;

            case RABBITMQ:
                // for example string "lorem.ipsum.*.sit":

                // split it up into ["lorem", "ipsum", "*", "sit"]
                String[] split = path.split("\\.");
                ArrayList<String> transformed = new ArrayList<>();
                // check for wildcards and replace with corresponding regex
                for (String s : split) {
                    switch (s) {
                        case "*":
                            transformed.add("[^.]+");
                            break;
                        case "#":
                            // TODO: make this work with zero-word
                            // e.g. "lorem.#.dolor" should ideally match "lorem.dolor"
                            transformed.add(".*");
                            break;
                        default:
                            transformed.add(s);
                            break;
                    }
                }
                // at this point, 'transformed' looks like ["lorem", "ipsum", "[^.]+", "sit"]
                StringJoiner sj = new StringJoiner("\\.");
                for (String s : transformed)
                    sj.add(s);
                String join = sj.toString();
                // join = "lorem\.ipsum\.[^.]+\.sit"

                ret = dest.matches(join);
                break;

            default:
                ret = false;
                break;
        }

        return ret;
    }

    private Completable subscribePath(String destinationPath, @Nullable List<StompHeader> headerList) {
        String topicId = UUID.randomUUID().toString();

        if (mTopics == null) mTopics = new HashMap<>();

        // Only continue if we don't already have a subscription to the topic
        if (mTopics.containsKey(destinationPath)) {
            Log.d(TAG, "Attempted to subscribe to already-subscribed path!");
            return Completable.complete();
        }

        mTopics.put(destinationPath, topicId);
        List<StompHeader> headers = new ArrayList<>();
        headers.add(new StompHeader(StompHeader.ID, topicId));
        headers.add(new StompHeader(StompHeader.DESTINATION, destinationPath));
        headers.add(new StompHeader(StompHeader.ACK, DEFAULT_ACK));
        if (headerList != null) headers.addAll(headerList);
        return send(new StompMessage(StompCommand.SUBSCRIBE,
                headers, null));
    }


    private Completable unsubscribePath(String dest) {
        mStreamMap.remove(dest);

        String topicId = mTopics.get(dest);
        Log.d(TAG, "Unsubscribe path: " + dest + " id: " + topicId);

        return send(new StompMessage(StompCommand.UNSUBSCRIBE,
                Collections.singletonList(new StompHeader(StompHeader.ID, topicId)), null));
    }

    public boolean isConnected() {
        return mConnected;
    }

    public boolean isConnecting() {
        return isConnecting;
    }
}
