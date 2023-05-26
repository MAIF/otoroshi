package otoroshi.next.plugins.grpc;

import com.google.common.collect.ImmutableList;
import io.grpc.stub.StreamObserver;

public class CompositeStreamObserver<T> implements StreamObserver<T> {
    private final ImmutableList<StreamObserver<T>> observers;

    @SafeVarargs
    public static <T> CompositeStreamObserver<T> of(StreamObserver<T>... observers) {
        return new CompositeStreamObserver<T>(ImmutableList.copyOf(observers));
    }

    private CompositeStreamObserver(ImmutableList<StreamObserver<T>> observers) {
        this.observers = observers;
    }

    @Override
    public void onCompleted() {
        for (StreamObserver<T> observer : observers) {
            try {
                observer.onCompleted();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        for (StreamObserver<T> observer : observers) {
            try {
                observer.onError(t);
            } catch (Throwable s) {
                s.printStackTrace();
            }
        }
    }

    @Override
    public void onNext(T value) {
        for (StreamObserver<T> observer : observers) {
            try {
                observer.onNext(value);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
