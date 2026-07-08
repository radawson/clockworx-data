package org.clockworx.data.hibernate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.clockworx.data.DatabaseSettings;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/**
 * Owns a lazily initialized Hibernate {@link SessionFactory} and provides
 * asynchronous transaction helpers with graceful shutdown handling.
 * <p>
 * This consolidates the transaction/executor pattern previously duplicated in
 * the plugins' {@code HibernateDatabaseManager} classes, including the shutdown
 * guards from the Werewolf implementation: once {@link #shutdown()} has begun,
 * new operations complete immediately with their fallback value, and
 * classloader-teardown errors ("zip file closed") are swallowed instead of
 * spamming the log during server shutdown.
 * <p>
 * The async {@link Executor} is supplied by the plugin (typically wrapping the
 * Paper async scheduler with an isEnabled guard) so this library stays free of
 * Bukkit/Paper dependencies.
 */
public class HibernateSessionManager {

    private final DatabaseSettings settings;
    private final List<Class<?>> entityClasses;
    private final Executor asyncExecutor;
    private final Logger logger;

    private volatile SessionFactory sessionFactory;
    private volatile boolean shuttingDown = false;
    private final Object initLock = new Object();

    /**
     * A unit of work executed inside a database transaction.
     *
     * @param <T> the result type
     */
    @FunctionalInterface
    public interface TransactionFunction<T> {
        T apply(Session session) throws Exception;
    }

    /**
     * A void unit of work executed inside a database transaction.
     */
    @FunctionalInterface
    public interface VoidTransactionFunction {
        void apply(Session session) throws Exception;
    }

    /**
     * Creates a new session manager. The SessionFactory is built lazily on first use.
     *
     * @param settings      the database connection settings
     * @param entityClasses the annotated JPA entity classes to register
     * @param asyncExecutor executor for asynchronous operations (plugin-supplied)
     * @param logger        the plugin logger
     */
    public HibernateSessionManager(DatabaseSettings settings, List<Class<?>> entityClasses,
                                   Executor asyncExecutor, Logger logger) {
        this.settings = settings;
        this.entityClasses = List.copyOf(entityClasses);
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    /**
     * Gets the SessionFactory, building it on first use (thread-safe).
     *
     * @return the SessionFactory
     * @throws IllegalStateException if the manager has been shut down or initialization fails
     */
    public SessionFactory getSessionFactory() {
        SessionFactory factory = sessionFactory;
        if (factory == null) {
            synchronized (initLock) {
                if (shuttingDown) {
                    throw new IllegalStateException("HibernateSessionManager has been shut down");
                }
                factory = sessionFactory;
                if (factory == null) {
                    factory = HibernateBootstrap.buildSessionFactory(settings, entityClasses, logger);
                    sessionFactory = factory;
                }
            }
        }
        return factory;
    }

    /**
     * @return true once {@link #shutdown()} has been initiated
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Executes a read-only unit of work asynchronously (no explicit transaction).
     * Errors are logged and resolved with the fallback value; during shutdown the
     * fallback is returned immediately.
     *
     * @param <T>      the result type
     * @param function the read to perform
     * @param fallback value to complete with on error or during shutdown
     * @return a future completing with the read result or the fallback
     */
    public <T> CompletableFuture<T> executeRead(TransactionFunction<T> function, T fallback) {
        if (shuttingDown) {
            return CompletableFuture.completedFuture(fallback);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (shuttingDown) {
                return fallback;
            }
            try (Session session = getSessionFactory().openSession()) {
                return function.apply(session);
            } catch (IllegalStateException e) {
                if (isClassloaderShutdownError(e)) {
                    logClassloaderShutdown(e);
                    return fallback;
                }
                throw e;
            } catch (Exception e) {
                if (!shuttingDown) {
                    logger.log(Level.SEVERE, "Database read failed", e);
                }
                return fallback;
            }
        }, asyncExecutor);
    }

    /**
     * Executes a unit of work asynchronously inside a transaction.
     * The transaction is rolled back on failure and the future completes
     * exceptionally; during shutdown the future completes with null immediately.
     *
     * @param <T>      the result type
     * @param function the transactional work to perform
     * @return a future completing with the transaction result
     */
    public <T> CompletableFuture<T> executeTransaction(TransactionFunction<T> function) {
        if (shuttingDown) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (shuttingDown) {
                return null;
            }
            Transaction tx = null;
            try (Session session = getSessionFactory().openSession()) {
                tx = session.beginTransaction();
                T result = function.apply(session);
                tx.commit();
                return result;
            } catch (IllegalStateException e) {
                if (isClassloaderShutdownError(e)) {
                    logClassloaderShutdown(e);
                    rollbackQuietly(tx);
                    return null;
                }
                throw e;
            } catch (Exception e) {
                rollback(tx);
                if (!shuttingDown) {
                    logger.log(Level.SEVERE, "Database transaction failed", e);
                }
                throw new RuntimeException("Database transaction failed", e);
            }
        }, asyncExecutor);
    }

    /**
     * Executes a void unit of work asynchronously inside a transaction.
     * The transaction is rolled back on failure and the future completes
     * exceptionally; during shutdown the future completes immediately.
     *
     * @param function the transactional work to perform
     * @return a future completing when the transaction is done
     */
    public CompletableFuture<Void> executeTransactionVoid(VoidTransactionFunction function) {
        if (shuttingDown) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            if (shuttingDown) {
                return;
            }
            Transaction tx = null;
            try (Session session = getSessionFactory().openSession()) {
                tx = session.beginTransaction();
                function.apply(session);
                tx.commit();
            } catch (IllegalStateException e) {
                if (isClassloaderShutdownError(e)) {
                    logClassloaderShutdown(e);
                    rollbackQuietly(tx);
                    return;
                }
                throw e;
            } catch (Exception e) {
                rollback(tx);
                if (!shuttingDown) {
                    logger.log(Level.SEVERE, "Database transaction failed", e);
                }
                throw new RuntimeException("Database transaction failed", e);
            }
        }, asyncExecutor);
    }

    /**
     * Shuts down the manager: marks it as shutting down (so in-flight and future
     * operations short-circuit) and closes the SessionFactory if it was built.
     * Safe to call more than once.
     */
    public void shutdown() {
        shuttingDown = true;
        synchronized (initLock) {
            if (sessionFactory != null && !sessionFactory.isClosed()) {
                logger.log(Level.INFO, "Shutting down Hibernate SessionFactory...");
                try {
                    sessionFactory.close();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error closing Hibernate SessionFactory", e);
                }
            }
            sessionFactory = null;
        }
    }

    /**
     * Detects the IllegalStateException thrown when the plugin's classloader is
     * torn down mid-operation during server shutdown.
     */
    private static boolean isClassloaderShutdownError(IllegalStateException e) {
        String message = e.getMessage();
        return message != null && (message.contains("zip file closed") || message.contains("classloader"));
    }

    private void logClassloaderShutdown(IllegalStateException e) {
        if (!shuttingDown) {
            logger.log(Level.WARNING, "Database operation interrupted by classloader shutdown", e);
        }
    }

    private void rollback(Transaction tx) {
        if (tx != null && tx.isActive()) {
            try {
                tx.rollback();
            } catch (Exception rollbackException) {
                logger.log(Level.SEVERE, "Transaction rollback failed", rollbackException);
            }
        }
    }

    private void rollbackQuietly(Transaction tx) {
        if (tx != null && tx.isActive()) {
            try {
                tx.rollback();
            } catch (Exception ignored) {
                // Ignore rollback errors during shutdown
            }
        }
    }
}
