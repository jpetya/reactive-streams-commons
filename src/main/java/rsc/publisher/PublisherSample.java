package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.subscriber.SerializedSubscriber;
import rsc.util.BackpressureHelper;
import rsc.subscriber.SubscriptionHelper;

/**
 * Samples the main source and emits its latest value whenever the other Publisher
 * signals a value.
 * <p>
 * <p>
 * Termination of either Publishers will result in termination for the Subscriber
 * as well.
 * <p>
 * <p>
 * Both Publishers will run in unbounded mode because the backpressure
 * would interfere with the sampling precision.
 * 
 * @param <T> the input and output value type
 * @param <U> the value type of the sampler (irrelevant)
 */
public final class PublisherSample<T, U> extends PublisherSource<T, T> {

    final Publisher<U> other;

    public PublisherSample(Publisher<? extends T> source, Publisher<U> other) {
        super(source);
        this.other = Objects.requireNonNull(other, "other");
    }

    @Override
    public long getPrefetch() {
        return Long.MAX_VALUE;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {

        Subscriber<T> serial = new SerializedSubscriber<>(s);

        PublisherSampleMainSubscriber<T> main = new PublisherSampleMainSubscriber<>(serial);

        s.onSubscribe(main);

        other.subscribe(new PublisherSampleOtherSubscriber<>(main));

        source.subscribe(main);
    }

    static final class PublisherSampleMainSubscriber<T>
      implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;

        volatile T value;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSampleMainSubscriber, Object> VALUE =
          AtomicReferenceFieldUpdater.newUpdater(PublisherSampleMainSubscriber.class, Object.class, "value");

        volatile Subscription main;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSampleMainSubscriber, Subscription> MAIN =
          AtomicReferenceFieldUpdater.newUpdater(PublisherSampleMainSubscriber.class, Subscription.class, "main");


        volatile Subscription other;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSampleMainSubscriber, Subscription> OTHER =
          AtomicReferenceFieldUpdater.newUpdater(PublisherSampleMainSubscriber.class, Subscription.class, "other");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherSampleMainSubscriber> REQUESTED =
          AtomicLongFieldUpdater.newUpdater(PublisherSampleMainSubscriber.class, "requested");

        public PublisherSampleMainSubscriber(Subscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (!MAIN.compareAndSet(this, null, s)) {
                s.cancel();
                if (main != SubscriptionHelper.cancelled()) {
                    SubscriptionHelper.reportSubscriptionSet();
                }
                return;
            }
            s.request(Long.MAX_VALUE);
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

        void setOther(Subscription s) {
            if (!OTHER.compareAndSet(this, null, s)) {
                s.cancel();
                if (other != SubscriptionHelper.cancelled()) {
                    SubscriptionHelper.reportSubscriptionSet();
                }
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
            }
        }

        @Override
        public void cancel() {
            cancelMain();
            cancelOther();
        }

        @Override
        public void onNext(T t) {
            value = t;
        }

        @Override
        public void onError(Throwable t) {
            cancelOther();

            actual.onError(t);
        }

        @Override
        public void onComplete() {
            cancelOther();

            actual.onComplete();
        }

        @SuppressWarnings("unchecked")
        T getAndNullValue() {
            return (T) VALUE.getAndSet(this, null);
        }

        void decrement() {
            REQUESTED.decrementAndGet(this);
        }
    }

    static final class PublisherSampleOtherSubscriber<T, U> implements Subscriber<U> {
        final PublisherSampleMainSubscriber<T> main;

        public PublisherSampleOtherSubscriber(PublisherSampleMainSubscriber<T> main) {
            this.main = main;
        }

        @Override
        public void onSubscribe(Subscription s) {
            main.setOther(s);
        }

        @Override
        public void onNext(U t) {
            PublisherSampleMainSubscriber<T> m = main;

            T v = m.getAndNullValue();

            if (v != null) {
                if (m.requested != 0L) {
                    m.actual.onNext(v);

                    if (m.requested != Long.MAX_VALUE) {
                        m.decrement();
                    }
                    return;
                }

                m.cancel();

                m.actual.onError(new IllegalStateException("Can't signal value due to lack of requests"));
            }
        }

        @Override
        public void onError(Throwable t) {
            PublisherSampleMainSubscriber<T> m = main;

            m.cancelMain();

            m.actual.onError(t);
        }

        @Override
        public void onComplete() {
            PublisherSampleMainSubscriber<T> m = main;

            m.cancelMain();

            m.actual.onComplete();
        }


    }
}
