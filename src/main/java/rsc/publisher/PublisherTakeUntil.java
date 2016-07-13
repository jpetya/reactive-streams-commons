package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.subscriber.SerializedSubscriber;
import rsc.subscriber.SubscriptionHelper;

/**
 * Relays values from the main Publisher until another Publisher signals an event.
 *
 * @param <T> the value type of the main Publisher
 * @param <U> the value type of the other Publisher
 */
public final class PublisherTakeUntil<T, U> extends PublisherSource<T, T> {

    final Publisher<U> other;

    public PublisherTakeUntil(Publisher<? extends T> source, Publisher<U> other) {
        super(source);
        this.other = Objects.requireNonNull(other, "other");
    }

    @Override
    public long getPrefetch() {
        return Long.MAX_VALUE;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        PublisherTakeUntilMainSubscriber<T> mainSubscriber = new PublisherTakeUntilMainSubscriber<>(s);

        PublisherTakeUntilOtherSubscriber<U> otherSubscriber = new PublisherTakeUntilOtherSubscriber<>(mainSubscriber);

        other.subscribe(otherSubscriber);

        source.subscribe(mainSubscriber);
    }

    static final class PublisherTakeUntilOtherSubscriber<U> implements Subscriber<U> {
        final PublisherTakeUntilMainSubscriber<?> main;

        boolean once;

        public PublisherTakeUntilOtherSubscriber(PublisherTakeUntilMainSubscriber<?> main) {
            this.main = main;
        }

        @Override
        public void onSubscribe(Subscription s) {
            main.setOther(s);

            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(U t) {
            onComplete();
        }

        @Override
        public void onError(Throwable t) {
            if (once) {
                return;
            }
            once = true;
            main.onError(t);
        }

        @Override
        public void onComplete() {
            if (once) {
                return;
            }
            once = true;
            main.onComplete();
        }


    }

    static final class PublisherTakeUntilMainSubscriber<T> implements Subscriber<T>, Subscription {
        final SerializedSubscriber<T> actual;

        volatile Subscription main;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherTakeUntilMainSubscriber, Subscription> MAIN =
          AtomicReferenceFieldUpdater.newUpdater(PublisherTakeUntilMainSubscriber.class, Subscription.class, "main");

        volatile Subscription other;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherTakeUntilMainSubscriber, Subscription> OTHER =
          AtomicReferenceFieldUpdater.newUpdater(PublisherTakeUntilMainSubscriber.class, Subscription.class, "other");

        public PublisherTakeUntilMainSubscriber(Subscriber<? super T> actual) {
            this.actual = new SerializedSubscriber<>(actual);
        }

        void setOther(Subscription s) {
            if (!OTHER.compareAndSet(this, null, s)) {
                s.cancel();
                if (other != SubscriptionHelper.cancelled()) {
                    SubscriptionHelper.reportSubscriptionSet();
                }
            }
        }

        @Override
        public void request(long n) {
            main.request(n);
        }

        void cancelMain() {
            Subscription s = main;
            if (s != SubscriptionHelper.cancelled()) {
                s = MAIN.getAndSet(this, SubscriptionHelper.cancelled());
                if (s != null && s != SubscriptionHelper.cancelled()) {
                    s.cancel();
                }
            }
        }

        void cancelOther() {
            Subscription s = other;
            if (s != SubscriptionHelper.cancelled()) {
                s = OTHER.getAndSet(this, SubscriptionHelper.cancelled());
                if (s != null && s != SubscriptionHelper.cancelled()) {
                    s.cancel();
                }
            }
        }

        @Override
        public void cancel() {
            cancelMain();
            cancelOther();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (!MAIN.compareAndSet(this, null, s)) {
                s.cancel();
                if (main != SubscriptionHelper.cancelled()) {
                    SubscriptionHelper.reportSubscriptionSet();
                }
            } else {
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {

            if (main == null) {
                if (MAIN.compareAndSet(this, null, SubscriptionHelper.cancelled())) {
                    SubscriptionHelper.error(actual, t);
                    return;
                }
            }
            cancel();

            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (main == null) {
                if (MAIN.compareAndSet(this, null, SubscriptionHelper.cancelled())) {
                    cancelOther();
                    SubscriptionHelper.complete(actual);
                    return;
                }
            }
            cancel();

            actual.onComplete();
        }
    }
}
