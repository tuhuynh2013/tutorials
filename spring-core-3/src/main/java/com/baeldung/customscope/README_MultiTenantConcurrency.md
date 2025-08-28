# Multi-Tenant Concurrent Request Handling

## What Happens When 2 Requests from Different Tenants Call at the Same Time?

When multiple requests from different tenants arrive simultaneously in a multi-tenant system, the behavior depends on the implementation's thread safety and isolation mechanisms. Here's what happens in a properly designed system:

### 1. Thread Isolation with ThreadLocal

Each request is handled by a separate thread, and tenant context is stored in `ThreadLocal`:

```java
// Thread 1 (Tenant A)                    // Thread 2 (Tenant B)
TenantContext.setCurrentTenant("A")       TenantContext.setCurrentTenant("B")
// Process request for Tenant A           // Process request for Tenant B
TenantContext.clear()                     TenantContext.clear()
```

**Key Points:**
- Each thread has its own isolated tenant context
- No cross-contamination between threads
- ThreadLocal ensures thread safety

### 2. Bean Scope Isolation

With proper tenant-scoped beans, each tenant gets its own instance:

```java
// Tenant A's thread gets:                // Tenant B's thread gets:
ServiceInstance_A (ID: abc123)            ServiceInstance_B (ID: def456)
Counter: 1, 2, 3...                       Counter: 1, 2, 3...
```

### 3. What Actually Happens - Step by Step

When two requests arrive simultaneously:

1. **Request Arrival** (T=0ms)
   - Request from Tenant A arrives → assigned to Thread-1
   - Request from Tenant B arrives → assigned to Thread-2

2. **Context Setting** (T=1ms)
   - Thread-1: `TenantContext.setCurrentTenant("tenant-A")`
   - Thread-2: `TenantContext.setCurrentTenant("tenant-B")`

3. **Bean Resolution** (T=2ms)
   - Thread-1: Spring looks up/creates bean for "tenant-A"
   - Thread-2: Spring looks up/creates bean for "tenant-B"
   - Each gets a different instance from the ConcurrentHashMap

4. **Processing** (T=3-100ms)
   - Both threads process in parallel
   - Each uses its own tenant-specific bean instance
   - No interference between threads

5. **Cleanup** (T=101ms)
   - Thread-1: `TenantContext.clear()`
   - Thread-2: `TenantContext.clear()`

### 4. Potential Issues in Poorly Designed Systems

#### Without ThreadLocal (WRONG):
```java
// Global variable - DON'T DO THIS!
private static String currentTenant;

// Thread 1 sets tenant A
currentTenant = "A";  
// Thread 2 overwrites with tenant B
currentTenant = "B";  
// Thread 1 now incorrectly uses tenant B's context!
```

#### Without Proper Synchronization (WRONG):
```java
// Using HashMap instead of ConcurrentHashMap
private Map<String, Object> tenantObjects = new HashMap<>();  // NOT thread-safe!
```

### 5. Best Practices for Concurrent Multi-Tenant Requests

1. **Always Use ThreadLocal for Request Context**
   ```java
   private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
   ```

2. **Use Thread-Safe Collections**
   ```java
   private final Map<String, Map<String, Object>> tenantScopedObjects = new ConcurrentHashMap<>();
   ```

3. **Implement Request Filters**
   ```java
   @Component
   public class TenantFilter implements Filter {
       @Override
       public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
           try {
               String tenantId = extractTenantId(request);
               TenantContext.setCurrentTenant(tenantId);
               chain.doFilter(request, response);
           } finally {
               TenantContext.clear(); // Always clear!
           }
       }
   }
   ```

4. **Database Connection Isolation**
   - Use separate connection pools per tenant
   - Or use schema/database separation
   - Set tenant context at connection level

5. **Resource Limits Per Tenant**
   - Implement rate limiting per tenant
   - Set connection pool limits
   - Monitor resource usage

### 6. Testing Concurrent Scenarios

Run the test to see this in action:
```bash
mvn test -Dtest=ConcurrentTenantRequestsTest
```

The test demonstrates:
- 5 tenants making 10 concurrent requests each
- Each tenant gets its own service instance
- No cross-tenant data leakage
- Proper isolation under high concurrency

### 7. Performance Considerations

**Advantages of This Approach:**
- True parallel processing (no blocking between tenants)
- Linear scalability with thread pool size
- Minimal overhead from ThreadLocal

**Potential Bottlenecks:**
- Shared database connections (use connection pooling)
- Shared caches (partition by tenant)
- Global locks (avoid or minimize)

### 8. Real-World Example Output

When running concurrent requests:
```
Tenant: tenant-1, Instance: a1b2c3d4, Count: 1, Data: Request-1
Tenant: tenant-2, Instance: e5f6g7h8, Count: 1, Data: Request-1
Tenant: tenant-1, Instance: a1b2c3d4, Count: 2, Data: Request-2
Tenant: tenant-2, Instance: e5f6g7h8, Count: 2, Data: Request-2
```

Notice:
- Different instance IDs for different tenants
- Independent counters
- Parallel execution
- No data mixing

### Summary

When properly implemented with ThreadLocal and thread-safe collections, concurrent requests from different tenants:
1. Execute in complete isolation
2. Process in parallel without blocking
3. Maintain data integrity
4. Scale linearly with available threads

The key is ensuring proper thread-local context management and using thread-safe data structures throughout the application.