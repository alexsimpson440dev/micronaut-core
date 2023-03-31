package io.micronaut.http.server.netty.body;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.netty.FormRouteCompleter;
import io.micronaut.http.server.netty.MicronautHttpData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Immediate {@link MultiObjectBody}, all operations are eager.
 */
@Internal
public final class ImmediateMultiObjectBody extends ManagedBody<List<?>> implements MultiObjectBody {
    ImmediateMultiObjectBody(List<?> objects) {
        super(objects);
    }

    @Override
    void release(List<?> value) {
        value.forEach(ReferenceCountUtil::release);
    }

    public ImmediateSingleObjectBody single(Charset defaultCharset, ByteBufAllocator alloc) {
        List<?> objects = prepareClaim();
        if (objects.isEmpty()) {
            return next(new ImmediateSingleObjectBody(null));
        }
        boolean allFormData = true;
        for (Object object : objects) {
            if (!(object instanceof MicronautHttpData<?>)) {
                allFormData = false;
                break;
            }
        }
        if (allFormData) {
            //noinspection unchecked
            return next(new ImmediateSingleObjectBody(toMap(defaultCharset, (List<? extends MicronautHttpData<?>>) objects)));
        }
        if (objects.size() == 1) {
            Object o = objects.get(0);
            return next(new ImmediateSingleObjectBody(o instanceof ByteBufHolder bbh ? bbh.content() : o));
        }
        return next(new ImmediateSingleObjectBody(coerceToComposite(objects, alloc)));
    }

    private static CompositeByteBuf coerceToComposite(List<?> objects, ByteBufAllocator alloc) {
        CompositeByteBuf composite = alloc.compositeBuffer();
        for (Object object : objects) {
            composite.addComponent(true, ((ByteBufHolder) object).content());
        }
        return composite;
    }

    public static Map<String, Object> toMap(Charset charset, Iterable<? extends MicronautHttpData<?>> dataList) {
        Map<String, Object> singleMap = new LinkedHashMap<>();
        Map<String, List<Object>> multiMap = new LinkedHashMap<>();
        for (MicronautHttpData<?> data : dataList) {
            String key = data.getName();
            String newValue;
            try {
                newValue = data.getString(charset);
            } catch (IOException e) {
                throw new InternalServerException("Error retrieving or decoding the value for: " + data.getName());
            }
            List<Object> multi = multiMap.get(key);
            if (multi != null) {
                multi.add(newValue);
            } else {
                Object existing = singleMap.put(key, newValue);
                if (existing != null) {
                    List<Object> combined = new ArrayList<>(2);
                    combined.add(existing);
                    combined.add(newValue);
                    singleMap.put(key, combined);
                    multiMap.put(key, combined);
                }
            }
        }
        return singleMap;
    }

    @Override
    public InputStream coerceToInputStream(ByteBufAllocator alloc) {
        List<?> objects = claim();
        ByteBuf buf = switch (objects.size()) {
            case 0 -> Unpooled.EMPTY_BUFFER;
            case 1 -> ((ByteBufHolder) objects.get(0)).content();
            default -> coerceToComposite(objects, alloc);
        };
        return new ByteBufInputStream(buf, true);
    }

    @Override
    public Publisher<?> asPublisher() {
        return Flux.fromIterable(claim());
    }

    @Override
    public MultiObjectBody mapNotNull(Function<Object, Object> transform) {
        return next(new ImmediateMultiObjectBody(prepareClaim().stream().map(transform).toList()));
    }

    @Override
    public void handleForm(FormRouteCompleter formRouteCompleter) {
        Flux.fromIterable(prepareClaim()).subscribe(formRouteCompleter);
        next(formRouteCompleter);
    }
}
