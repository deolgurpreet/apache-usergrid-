/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.usergrid.management.importer;


import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.usergrid.corepersistence.util.CpNamingUtils;
import org.apache.usergrid.persistence.EntityManager;
import org.apache.usergrid.persistence.EntityManagerFactory;
import org.apache.usergrid.persistence.entities.FailedImportConnection;
import org.apache.usergrid.persistence.entities.FailedImportEntity;
import org.apache.usergrid.persistence.entities.FileImport;
import org.apache.usergrid.persistence.exceptions.EntityNotFoundException;
import org.apache.usergrid.persistence.exceptions.PersistenceException;


/**
 * Statistics used to track a file import. Only 1 instance of this class should exist
 * per file imported in the cluster. There is a direct 1-1 mapping of the statistics provided
 * here and the file import status. This class is thread-safe to be used across multiple threads.
 */
public class FileImportStatistics {

    private static final String ERROR_MESSAGE =
        "Failed to import some data.  See the import counters and errors.";

    private static final String ERRORS_CONNECTION_NAME = "errors";

    private final AtomicLong entitiesWritten = new AtomicLong( 0 );
    private final AtomicLong entitiesFailed = new AtomicLong( 0 );
    private final AtomicLong connectionsWritten = new AtomicLong( 0 );
    private final AtomicLong connectionsFailed = new AtomicLong( 0 );
    private final AtomicInteger cachedOperations = new AtomicInteger( 0 );

    private final Semaphore writeSemaphore = new Semaphore( 1 );

    private final FileImport fileImport;
    private final EntityManagerFactory emf;
    private final int flushCount;


    /**
     * Create an instance to track counters.   Note that when this instance is created, it will
     * attempt to load it's state from the entity manager.  In the case of using this when resuming,
     * be sure you begin processing where the system thinks * it has left off.
     *
     * @param emf Entity Manager Factory
     * @param fileImport File Import Entity
     * @param flushCount The number of success + failures to accumulate before flushing
     */
    public FileImportStatistics(
        final EntityManagerFactory emf, final FileImport fileImport, final int flushCount ) {

        this.emf = emf;
        this.flushCount = flushCount;
        this.fileImport = fileImport;

        this.entitiesWritten.addAndGet( fileImport.getImportedEntityCount() );
        this.entitiesFailed.addAndGet( fileImport.getFailedEntityCount() );

        this.connectionsWritten.addAndGet( fileImport.getImportedConnectionCount() );
        this.connectionsFailed.addAndGet( fileImport.getFailedConnectionCount() );
    }


    /**
     * Invoke when an entity has been successfully written
     */
    public void entityWritten() {
        entitiesWritten.incrementAndGet();
        maybeFlush();
    }


    /**
     * Invoke when an entity fails to write correctly
     */

    public void entityFailed( final String message ) {
        entitiesFailed.incrementAndGet();

        FailedImportEntity failedImportEntity = new FailedImportEntity();
        failedImportEntity.setErrorMessage( message );

        try {
            EntityManager entityManager = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
            failedImportEntity = entityManager.create( failedImportEntity );
            entityManager.createConnection( fileImport, ERRORS_CONNECTION_NAME, failedImportEntity );
        }
        catch ( Exception e ) {
            throw new PersistenceException( "Unable to save failed entity import message", e );
        }
        maybeFlush();
    }


    /**
     * Invoked when a connection is written
     */
    public void connectionWritten() {
        connectionsWritten.incrementAndGet();
        maybeFlush();
    }


    /**
     * Invoked when a connection cannot be written
     */
    public void connectionFailed( final String message ) {
        connectionsFailed.incrementAndGet();


        FailedImportConnection failedImportConnection = new FailedImportConnection();
        failedImportConnection.setErrorMessage( message );

        try {
            EntityManager entityManager = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
            failedImportConnection = entityManager.create( failedImportConnection );
            entityManager.createConnection( fileImport, ERRORS_CONNECTION_NAME, failedImportConnection );
        }
        catch ( Exception e ) {
            throw new PersistenceException( "Unable to save failed entity import message", e );
        }
        maybeFlush();
    }


    /**
     * Invoke when the file is completed processing
     */
    public void complete() {

        final long failed = entitiesFailed.get() + connectionsFailed.get();

        final FileImport.State state;
        final String message;

        if ( failed > 0 ) {
            state = FileImport.State.FAILED;
            message = ERROR_MESSAGE;
        }
        else {
            state = FileImport.State.FINISHED;
            message = null;
        }

        updateFileImport( state, message );
    }


    /**
     * Invoke when we halt the import with a fatal error that cannot be recovered.
     */
    public void fatal( final String message ) {

        updateFileImport( FileImport.State.FAILED, message );
    }


    /**
     * Return the total number of successful imports + failed imports.  Can be used in resume. Note that this reflects
     * the counts last written to cassandra when this instance was created + any processing
     */
    public long getTotalEntityCount() {
        return  getEntitiesWritten() + getEntitiesFailed();
    }


    /**
     * Get the total number of failed + successful connections
     * @return
     */
    public long getTotalConnectionCount(){
        return getConnectionsFailed() + getConnectionsWritten();
    }


    /**
     * Returns true if we should stop processing.  This will use the following logic
     *
     * We've attempted to import over 1k entities After 1k, we have over a 50% failure rate
     */
    public boolean shouldStopProcessingEntities() {

        //TODO Dave, George.  What algorithm should we use here?
        return false;
    }


    /**
     * Returns true if we should stop processing.  This will use the following logic
     *
     * We've attempted to import over 1k connections After 1k, we have over a 50% failure rate
     */
    public boolean shouldStopProcessingConnections() {

        //TODO Dave, George.  What algorithm should we use here?
        return false;
    }

    /**
     * Get the number of entities written
     * @return
     */
    public long getEntitiesWritten() {
        return entitiesWritten.get();
    }


    /**
     * Get the number of failed entities
     * @return
     */
    public long getEntitiesFailed() {
        return entitiesFailed.get();
    }


    /**
     * Get the number of connections written
     * @return
     */
    public long getConnectionsWritten() {
        return connectionsWritten.get();
    }


    /**
     * Get the number of connections failed
     * @return
     */
    public long getConnectionsFailed() {
        return connectionsFailed.get();
    }

    private void maybeFlush() {
        final int count = cachedOperations.incrementAndGet();

        //no op
        if ( count < flushCount ) {
            return;
        }

        //another thread is writing, no op, just return
        if ( !writeSemaphore.tryAcquire() ) {
            return;
        }

        final long failed = entitiesFailed.get();
        final long written = entitiesWritten.get();
        final String message;

        if ( failed > 0 ) {
            message = "Failed to import " + failed + " entities.  Successfully imported " + written + " entities";
        }
        else {
            message = "Successfully imported " + written + " entities";
        }

        updateFileImport( FileImport.State.STARTED, message );
        cachedOperations.addAndGet( flushCount * -1 );
        writeSemaphore.release();
    }


    /**
     * Update the file import status with the provided messages
     *
     * @param state The state to set into the import
     * @param message The message to set
     */
    private void updateFileImport( final FileImport.State state, final String message ) {

        try {


            final long writtenEntities = entitiesWritten.get();
            final long failedEntities = entitiesFailed.get();

            final long writtenConnections = connectionsFailed.get();
            final long failedConnections = connectionsFailed.get();


            fileImport.setImportedEntityCount( writtenEntities );
            fileImport.setFailedEntityCount( failedEntities );
            fileImport.setImportedConnectionCount( writtenConnections );
            fileImport.setFailedConnectionCount( failedConnections );
            fileImport.setState( state );
            fileImport.setErrorMessage( message );

            EntityManager entityManager = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
            entityManager.update( fileImport );
        }
        catch ( Exception e ) {
            throw new RuntimeException( "Unable to persist complete state", e );
        }
    }
}