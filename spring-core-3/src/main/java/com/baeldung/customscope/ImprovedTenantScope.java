package com.baeldung.customscope;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

/**
 * Improved tenant scope that properly handles concurrent requests from different tenants.
 * Each tenant gets its own isolated set of beans.
 */
public class ImprovedTenantScope implements Scope {

    // Outer map: tenantId -> (beanName -> bean instance)
    private final Map<String, Map<String, Object>> tenantScopedObjects = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Runnable>> tenantDestructionCallbacks = new ConcurrentHashMap<>();

    @Override
    public Object get(String name, ObjectFactory<?> objectFactory) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set for current thread");
        }
        
        Map<String, Object> tenantObjects = tenantScopedObjects.computeIfAbsent(
            tenantId, 
            k -> new ConcurrentHashMap<>()
        );
        
        return tenantObjects.computeIfAbsent(name, k -> objectFactory.getObject());
    }

    @Override
    public Object remove(String name) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            return null;
        }
        
        Map<String, Runnable> callbacks = tenantDestructionCallbacks.get(tenantId);
        if (callbacks != null) {
            callbacks.remove(name);
        }
        
        Map<String, Object> tenantObjects = tenantScopedObjects.get(tenantId);
        if (tenantObjects != null) {
            return tenantObjects.remove(name);
        }
        
        return null;
    }

    @Override
    public void registerDestructionCallback(String name, Runnable callback) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set for current thread");
        }
        
        Map<String, Runnable> callbacks = tenantDestructionCallbacks.computeIfAbsent(
            tenantId,
            k -> new ConcurrentHashMap<>()
        );
        callbacks.put(name, callback);
    }

    @Override
    public Object resolveContextualObject(String key) {
        if ("tenantId".equals(key)) {
            return TenantContext.getCurrentTenant();
        }
        return null;
    }

    @Override
    public String getConversationId() {
        return TenantContext.getCurrentTenant();
    }
    
    /**
     * Clears all objects and callbacks for a specific tenant.
     * This should be called when a tenant is being removed from the system.
     * 
     * @param tenantId the tenant identifier
     */
    public void clearTenant(String tenantId) {
        Map<String, Runnable> callbacks = tenantDestructionCallbacks.remove(tenantId);
        if (callbacks != null) {
            callbacks.values().forEach(Runnable::run);
        }
        tenantScopedObjects.remove(tenantId);
    }
}