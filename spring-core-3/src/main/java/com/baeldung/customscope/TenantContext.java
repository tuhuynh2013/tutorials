package com.baeldung.customscope;

/**
 * Thread-safe tenant context holder using ThreadLocal.
 * This ensures that each thread (request) has its own isolated tenant identifier.
 */
public class TenantContext {
    
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
    
    /**
     * Sets the tenant identifier for the current thread.
     * This is typically called at the beginning of a request.
     * 
     * @param tenantId the tenant identifier
     */
    public static void setCurrentTenant(String tenantId) {
        currentTenant.set(tenantId);
    }
    
    /**
     * Gets the tenant identifier for the current thread.
     * 
     * @return the current tenant identifier, or null if not set
     */
    public static String getCurrentTenant() {
        return currentTenant.get();
    }
    
    /**
     * Clears the tenant identifier for the current thread.
     * This should be called at the end of a request to prevent memory leaks.
     */
    public static void clear() {
        currentTenant.remove();
    }
}