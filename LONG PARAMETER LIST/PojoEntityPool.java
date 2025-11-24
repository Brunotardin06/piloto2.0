/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.terasology.engine.entitySystem.entity.internal;

import java.util.Collections;                 // Collections: utility methods for unmodifiable views and helpers on collections
import java.util.Map;                        // Map: core mapping from entity id (Long) to BaseEntityRef
import java.util.Optional;                   // Optional: explicit “present/absent” result for remove operations

import com.google.common.collect.MapMaker;   // MapMaker (Guava): builds concurrent map with weak values for entity references
import org.joml.Quaternionfc;                // Quaternionfc (JOML): immutable rotation interface for world transforms
import org.joml.Vector3fc;                   // Vector3fc (JOML): immutable 3D vector interface for world positions
import org.slf4j.Logger;                     // Logger (SLF4J): logging façade used for warnings and diagnostics
import org.slf4j.LoggerFactory;              // LoggerFactory (SLF4J): factory for obtaining Logger instances
import org.terasology.engine.entitySystem.component.Component;          // Component: base interface for data components attached to entities
import org.terasology.engine.entitySystem.entity.EntityBuilder;         // EntityBuilder: builder used to assemble entities and components
import org.terasology.engine.entitySystem.entity.EntityRef;             // EntityRef: handle/safe reference to an entity id
import org.terasology.engine.entitySystem.event.EventSystem;            // EventSystem: dispatches lifecycle and gameplay events to systems
import org.terasology.engine.entitySystem.prefab.Prefab;                // Prefab: predefined template of components for entity creation
import org.terasology.engine.logic.location.LocationComponent;          // LocationComponent: component holding position/rotation in the world

/**
 * Concrete implementation of {@link EngineEntityPool} backed by plain Java (POJO) data structures.
 * <p>
 * This pool is responsible for:
 * <ul>
 *   <li>Owning in-memory {@link EntityRef} instances created by a {@link PojoEntityManager}.</li>
 *   <li>Storing components in a shared {@link ComponentTable} keyed by entity id.</li>
 *   <li>Creating, tracking and destroying entities and their components.</li>
 * </ul>
 */
public class PojoEntityPool implements EngineEntityPool {

    private static final Logger logger = LoggerFactory.getLogger(PojoEntityPool.class);

    /**
     * Manager that owns this pool and coordinates ids, prefabs and events.
     */
    private final PojoEntityManager entityManager;

    /**
     * Backing store for all active entity refs in this pool.
     * <p>
     * Weak values allow unused {@link BaseEntityRef} objects to be garbage collected,
     * while the raw component data still lives in {@link ComponentTable}.
     */
    private final Map<Long, BaseEntityRef> entityStore =
            new MapMaker()
                    .weakValues()
                    .concurrencyLevel(4)
                    .initialCapacity(1000)
                    .makeMap();

    /**
     * Central storage for all components attached to entities in this pool.
     */
    private final ComponentTable componentStore = new ComponentTable();

    public PojoEntityPool(PojoEntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Completely clears this pool:
     * <ul>
     *   <li>Invalidates all existing {@link EntityRef} instances.</li>
     *   <li>Removes all components from {@link ComponentTable}.</li>
     *   <li>Empties the internal entity map.</li>
     * </ul>
     * Typically used on world reset/shutdown.
     */
    @Override
    public void clear() {
        for (EntityRef entity : entityStore.values()) {
            entity.invalidate();
        }
        componentStore.clear();
        entityStore.clear();
    }

    /**
     * Creates a bare entity with no prefab and no transform information.
     */
    @Override
    public EntityRef create() {
        return create((Prefab) null, null, null);
    }

    /**
     * Creates an entity directly from a {@link Prefab}, without initial position/rotation.
     */
    @Override
    public EntityRef create(Prefab prefab) {
        return create(prefab, null, null);
    }

    /**
     * Creates an entity from a prefab with optional position and rotation.
     */
    @Override
    public EntityRef create(Prefab prefab, Vector3fc position, Quaternionfc rotation) {
        return createInternal(prefab, null, position, rotation, true);
    }

    /**
     * Creates an entity from a set of components, optionally emitting lifecycle events.
     * This path bypasses prefabs and is often used by systems or tools.
     */
    @Override
    public EntityRef create(Iterable<Component> components, boolean sendLifecycleEvents) {
        EntityBuilder builder = newBuilder();
        builder.addComponents(components);
        builder.setSendLifecycleEvents(sendLifecycleEvents);
        return builder.build();
    }

    /**
     * Internal helper that centralizes creation logic for all variants.
     * <p>
     * It:
     * <ul>
     *   <li>Resolves a {@link Prefab} either from a direct reference or by name.</li>
     *   <li>Bootstraps an {@link EntityBuilder} for the prefab and lifecycle flag.</li>
     *   <li>Ensures a {@link LocationComponent} exists if we want to set position/rotation.</li>
     * </ul>
     */
    private EntityRef createInternal(Prefab prefab,
                                     String prefabName,
                                     Vector3fc position,
                                     Quaternionfc rotation,
                                     boolean sendLifecycleEvents) {

        Prefab resolvedPrefab = prefab;

        // Resolve prefab by name via the manager if a name was provided instead of a direct Prefab.
        if (resolvedPrefab == null && prefabName != null && !prefabName.isEmpty()) {
            resolvedPrefab = entityManager.getPrefabManager().getPrefab(prefabName);
            if (resolvedPrefab == null) {
                logger.warn("Unable to instantiate unknown prefab: \"{}\"", prefabName);
                return EntityRef.NULL;
            }
        }

        // Build the entity from the resolved prefab.
        EntityBuilder builder = newBuilder(resolvedPrefab);
        builder.setSendLifecycleEvents(sendLifecycleEvents);

        // If we want to apply a transform, make sure a LocationComponent exists.
        LocationComponent loc = builder.getComponent(LocationComponent.class);
        if (loc == null && (position != null || rotation != null)) {
            loc = new LocationComponent();
            builder.addComponent(loc);
        }

        if (position != null) {
            loc.setWorldPosition(position);
        }
        if (rotation != null) {
            loc.setWorldRotation(rotation);
        }

        return builder.build();
    }

    /**
     * Destroys this entity and sends standard “about to be removed” events.
     *
     * @param entityId id of the entity to destroy
     */
    @Override
    public void destroy(long entityId) {
        if (!entityManager.idLoaded(entityId)) {
            return;
        }

        EntityRef ref = getEntity(entityId);
        EventSystem eventSystem = entityManager.getEventSystem();

        // Notify systems that the entity is about to be deactivated/removed.
        if (eventSystem != null) {
            eventSystem.send(ref, BeforeDeactivateComponent.newInstance());
            eventSystem.send(ref, BeforeRemoveComponent.newInstance());
        }

        entityManager.notifyComponentRemovalAndEntityDestruction(entityId, ref);
        destroy(ref);
    }

    /**
     * Low-level destruction that assumes all notifications have already been sent.
     * Removes the entity from this pool and invalidates the reference.
     */
    private void destroy(EntityRef ref) {
        long entityId = ref.getId();
        entityStore.remove(entityId);
        entityManager.unregister(entityId);
        ref.invalidate();
        componentStore.remove(entityId);
    }

    /**
     * Creates an entity without emitting lifecycle events, but still informs entity-lifecycle subscribers.
     * Often used in internal tooling or bulk loading where event noise is undesirable.
     */
    @Override
    public EntityRef createEntityWithoutLifecycleEvents(Iterable<Component> components) {
        return create(components, false);
    }

    /**
     * Creates a new {@link EntityBuilder} that will build entities attached to this pool.
     */
    @Override
    public EntityBuilder newBuilder() {
        return new EntityBuilder(entityManager, this);
    }

    /**
     * Creates a builder pre-configured with the given prefab instance.
     * Useful when the caller already resolved the prefab via the prefab manager.
     */
    @Override
    public EntityBuilder newBuilder(Prefab prefab) {
        EntityBuilder builder = newBuilder();
        builder.addPrefab(prefab);
        return builder;
    }

    /**
     * Exposes an unmodifiable view of the internal entity store.
     * Mainly for diagnostics / controlled iteration.
     */
    protected Map<Long, BaseEntityRef> getEntityStore() {
        return Collections.unmodifiableMap(entityStore);
    }

    /**
     * Inserts a new reference into the internal store.
     * Intended to be called by {@link PojoEntityManager}.
     */
    @Override
    public void putEntity(long entityId, BaseEntityRef ref) {
        entityStore.put(entityId, ref);
    }

    @Override
    public ComponentTable getComponentStore() {
        return componentStore;
    }

    /**
     * Returns an {@link EntityRef} for the given id, creating and caching it if necessary.
     * If the id is not known to the manager, {@link EntityRef#NULL} is returned.
     */
    @Override
    public EntityRef getEntity(long entityId) {
        if (entityId == NULL_ID || !entityManager.isExistingEntity(entityId)) {
            return EntityRef.NULL;
        }

        EntityRef existing = entityStore.get(entityId);
        if (existing != EntityRef.NULL && existing != null) {
            return existing;
        }

        // Lazily create a new ref via the configured strategy, then register it in this pool.
        BaseEntityRef entity = entityManager.getEntityRefStrategy().createRefFor(entityId, entityManager);
        entityStore.put(entityId, entity);
        entityManager.assignToPool(entityId, this);
        return entity;
    }

    /**
     * Returns a lazy iterable of all entities that have all of the given component types.
     * Filtering happens over current {@link #entityStore} keys, using {@link ComponentTable} as backing data.
     */
    @SafeVarargs
    @Override
    public final Iterable<EntityRef> getEntitiesWith(Class<? extends Component>... componentClasses) {
        return () -> entityStore.keySet()
                .stream()
                .filter(id -> {
                    for (Class<? extends Component> component : componentClasses) {
                        if (componentStore.get(id, component) == null) {
                            return false;
                        }
                    }
                    return true;
                })
                .map(this::getEntity)
                .iterator();
    }

    /**
     * Returns an iterable over all active entities in this pool.
     * Backed by an {@link EntityIterator} over the component table’s entity ids.
     */
    @Override
    public Iterable<EntityRef> getAllEntities() {
        return () -> new EntityIterator(componentStore.entityIdIterator(), this);
    }

    /**
     * Removes all data for an entity id from this pool and returns the previous ref (if any).
     * Also unassigns the pool for that id in the manager.
     */
    @Override
    public Optional<BaseEntityRef> remove(long id) {
        componentStore.remove(id);
        entityManager.unassignPool(id);
        return Optional.ofNullable(entityStore.remove(id));
    }

    /**
     * Inserts a fully constructed {@link BaseEntityRef} and all its components into this pool.
     * Used when rebuilding entities from external sources (e.g. persistence).
     */
    @Override
    public void insertRef(BaseEntityRef ref, Iterable<Component> components) {
        entityStore.put(ref.getId(), ref);
        components.forEach(comp -> componentStore.put(ref.getId(), comp));
        entityManager.assignToPool(ref.getId(), this);
    }

    /**
     * Whether this pool currently holds a reference for the given entity id.
     */
    @Override
    public boolean contains(long id) {
        return entityStore.containsKey(id);
    }
}
