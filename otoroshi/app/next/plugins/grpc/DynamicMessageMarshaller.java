package otoroshi.next.plugins.grpc;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistryLite;
import io.grpc.MethodDescriptor;

import java.io.IOException;
import java.io.InputStream;

public class DynamicMessageMarshaller implements MethodDescriptor.Marshaller<DynamicMessage> {
    private final Descriptors.Descriptor messageDescriptor;

    public DynamicMessageMarshaller(Descriptors.Descriptor messageDescriptor) {
        this.messageDescriptor = messageDescriptor;
    }

    @Override
    public DynamicMessage parse(InputStream inputStream) {
        try {
            return DynamicMessage.newBuilder(messageDescriptor)
                    .mergeFrom(inputStream, ExtensionRegistryLite.getEmptyRegistry())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Unable to merge from the supplied input stream", e);
        }
    }

    @Override
    public InputStream stream(DynamicMessage abstractMessage) {
        return abstractMessage.toByteString().newInput();
    }
}