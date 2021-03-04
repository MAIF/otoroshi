package  otoroshi.utils.misc;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import otoroshi.models.ServiceDescriptor;
import otoroshi.models.ServiceDescriptorQuery;
import scala.Option;

import java.util.concurrent.TimeUnit;

public class LocalCache {

    public static Cache<String, Object> findAllCache = CacheBuilder
        .newBuilder()
        .maximumSize(99999)
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .build();

    public static Cache<String, Object> allServices = CacheBuilder
            .newBuilder()
            .maximumSize(99999)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    public static Cache<ServiceDescriptorQuery, Option<ServiceDescriptor>> descriptorCache = CacheBuilder
            .newBuilder()
            .maximumSize(99999)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .build();
}
