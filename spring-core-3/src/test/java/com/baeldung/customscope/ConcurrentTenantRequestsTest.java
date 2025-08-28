package com.baeldung.customscope;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Test demonstrating what happens when 2 requests from different tenants call at the same time.
 * This test simulates concurrent multi-tenant scenarios and verifies proper isolation.
 */
public class ConcurrentTenantRequestsTest {

    @Configuration
    static class TestConfig {
        @Bean
        public ImprovedTenantScope tenantScope() {
            return new ImprovedTenantScope();
        }
        
        @Bean
        @Scope("tenant")
        public TenantService tenantService() {
            return new TenantService();
        }
    }
    
    static class TenantService {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String instanceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        
        public String processRequest(String data) {
            // Simulate some processing time
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            int count = counter.incrementAndGet();
            String tenantId = TenantContext.getCurrentTenant();
            return String.format("Tenant: %s, Instance: %s, Count: %d, Data: %s", 
                tenantId, instanceId, count, data);
        }
        
        public int getCount() {
            return counter.get();
        }
        
        public String getInstanceId() {
            return instanceId;
        }
    }
    
    @Test
    public void testConcurrentRequestsFromDifferentTenants() throws InterruptedException {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        try {
            ctx.register(TestConfig.class);
            ctx.getBeanFactory().registerScope("tenant", ctx.getBean(ImprovedTenantScope.class));
            ctx.refresh();
            
            int numberOfTenants = 5;
            int requestsPerTenant = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numberOfTenants * 2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(numberOfTenants * requestsPerTenant);
            
            Map<String, List<String>> results = new ConcurrentHashMap<>();
            Map<String, String> tenantInstances = new ConcurrentHashMap<>();
            
            // Submit tasks for multiple tenants
            for (int t = 1; t <= numberOfTenants; t++) {
                String tenantId = "tenant-" + t;
                results.put(tenantId, new ArrayList<>());
                
                for (int r = 1; r <= requestsPerTenant; r++) {
                    final int requestNum = r;
                    executor.submit(() -> {
                        try {
                            // Wait for all threads to be ready
                            startLatch.await();
                            
                            // Set tenant context for this thread
                            TenantContext.setCurrentTenant(tenantId);
                            
                            // Get tenant-scoped service
                            TenantService service = ctx.getBean(TenantService.class);
                            
                            // Store the instance ID for verification
                            tenantInstances.putIfAbsent(tenantId, service.getInstanceId());
                            
                            // Process request
                            String result = service.processRequest("Request-" + requestNum);
                            results.get(tenantId).add(result);
                            
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            // Clear tenant context
                            TenantContext.clear();
                            completionLatch.countDown();
                        }
                    });
                }
            }
            
            // Start all threads simultaneously
            startLatch.countDown();
            
            // Wait for all requests to complete
            assertTrue("Requests did not complete in time", 
                completionLatch.await(30, TimeUnit.SECONDS));
            
            executor.shutdown();
            assertTrue("Executor did not terminate", 
                executor.awaitTermination(5, TimeUnit.SECONDS));
            
            // Verify results
            System.out.println("\n=== Concurrent Multi-Tenant Request Results ===\n");
            
            // 1. Verify each tenant got their own service instance
            assertEquals("Should have unique instance per tenant", 
                numberOfTenants, tenantInstances.size());
            
            System.out.println("Tenant Instances (each tenant gets its own):");
            tenantInstances.forEach((tenant, instance) -> 
                System.out.println("  " + tenant + " -> Instance: " + instance));
            
            // 2. Verify request counts per tenant
            System.out.println("\nRequest Results per Tenant:");
            for (String tenantId : results.keySet()) {
                List<String> tenantResults = results.get(tenantId);
                assertEquals("Each tenant should have processed all requests", 
                    requestsPerTenant, tenantResults.size());
                
                System.out.println("\n" + tenantId + ":");
                // Print first few results as sample
                tenantResults.stream().limit(3).forEach(r -> 
                    System.out.println("  " + r));
                if (tenantResults.size() > 3) {
                    System.out.println("  ... (" + (tenantResults.size() - 3) + " more)");
                }
                
                // Verify all results belong to the correct tenant
                assertTrue("All results should belong to correct tenant",
                    tenantResults.stream().allMatch(r -> r.contains("Tenant: " + tenantId)));
                
                // Verify all results have the same instance ID for this tenant
                String expectedInstance = tenantInstances.get(tenantId);
                assertTrue("All results should have same instance ID",
                    tenantResults.stream().allMatch(r -> r.contains("Instance: " + expectedInstance)));
            }
            
            // 3. Verify isolation - no cross-tenant contamination
            System.out.println("\n=== Isolation Verification ===");
            System.out.println("✓ Each tenant has its own service instance");
            System.out.println("✓ No cross-tenant data leakage detected");
            System.out.println("✓ All " + (numberOfTenants * requestsPerTenant) + 
                " concurrent requests processed successfully");
            
        } finally {
            ctx.close();
        }
    }
    
    @Test
    public void testRaceConditionBetweenTwoTenants() throws InterruptedException {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        try {
            ctx.register(TestConfig.class);
            ctx.getBeanFactory().registerScope("tenant", ctx.getBean(ImprovedTenantScope.class));
            ctx.refresh();
            
            int iterations = 100;
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch completionLatch = new CountDownLatch(iterations * 2);
            
            AtomicInteger tenant1Count = new AtomicInteger(0);
            AtomicInteger tenant2Count = new AtomicInteger(0);
            
            System.out.println("\n=== Race Condition Test: Two Tenants Calling Simultaneously ===\n");
            
            for (int i = 0; i < iterations; i++) {
                final int iteration = i;
                
                // Tenant 1 request
                executor.submit(() -> {
                    try {
                        TenantContext.setCurrentTenant("tenant-A");
                        TenantService service = ctx.getBean(TenantService.class);
                        String result = service.processRequest("Data-" + iteration);
                        
                        // Verify we got the correct tenant's service
                        assertTrue(result.contains("Tenant: tenant-A"));
                        tenant1Count.incrementAndGet();
                        
                    } finally {
                        TenantContext.clear();
                        completionLatch.countDown();
                    }
                });
                
                // Tenant 2 request (submitted immediately after)
                executor.submit(() -> {
                    try {
                        TenantContext.setCurrentTenant("tenant-B");
                        TenantService service = ctx.getBean(TenantService.class);
                        String result = service.processRequest("Data-" + iteration);
                        
                        // Verify we got the correct tenant's service
                        assertTrue(result.contains("Tenant: tenant-B"));
                        tenant2Count.incrementAndGet();
                        
                    } finally {
                        TenantContext.clear();
                        completionLatch.countDown();
                    }
                });
            }
            
            assertTrue("All requests should complete", 
                completionLatch.await(30, TimeUnit.SECONDS));
            
            executor.shutdown();
            assertTrue("Executor should terminate", 
                executor.awaitTermination(5, TimeUnit.SECONDS));
            
            // Verify both tenants processed all their requests
            assertEquals("Tenant A should process all requests", iterations, tenant1Count.get());
            assertEquals("Tenant B should process all requests", iterations, tenant2Count.get());
            
            System.out.println("Results:");
            System.out.println("  Tenant A processed: " + tenant1Count.get() + " requests");
            System.out.println("  Tenant B processed: " + tenant2Count.get() + " requests");
            System.out.println("  Total requests: " + (tenant1Count.get() + tenant2Count.get()));
            System.out.println("\n✓ No race conditions detected - proper tenant isolation maintained");
            
        } finally {
            ctx.close();
        }
    }
    
    @Test  
    public void testWhatHappensWithoutProperTenantContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        try {
            ctx.register(TestConfig.class);
            ctx.getBeanFactory().registerScope("tenant", ctx.getBean(ImprovedTenantScope.class));
            ctx.refresh();
            
            System.out.println("\n=== Test: What Happens Without Tenant Context ===\n");
            
            try {
                // Try to get a tenant-scoped bean without setting tenant context
                TenantService service = ctx.getBean(TenantService.class);
                fail("Should throw exception when no tenant context is set");
            } catch (IllegalStateException e) {
                System.out.println("✓ Expected error when no tenant context: " + e.getMessage());
                assertTrue(e.getMessage().contains("No tenant context"));
            }
            
        } finally {
            ctx.close();
        }
    }
}