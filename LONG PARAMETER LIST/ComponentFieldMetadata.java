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

package org.terasology.engine.entitySystem.metadata; // Adjust to the actual package in your project

import java.lang.reflect.Field;                  // Field (JDK reflection): represents the declared component field being described
import java.lang.reflect.Type;                   // Type (JDK reflection): generic type information used to inspect collections/maps

import java.util.Collection;                     // Collection (JDK): used to detect fields that are collections of EntityRef
import java.util.Map;                            // Map (JDK): used to detect fields that are maps whose values are EntityRef

import org.terasology.engine.entitySystem.component.Component; // Component: base type for all ECS components in this engine
import org.terasology.engine.entitySystem.entity.EntityRef;     // EntityRef: handle pointing to an entity in the entity system
import org.terasology.engine.entitySystem.entity.Owns;          // Owns: annotation marking that a field owns the referenced entities

import org.terasology.engine.reflection.metadata.ClassMetadata;         // ClassMetadata: metadata model describing a component class
import org.terasology.engine.reflection.metadata.ReplicatedFieldMetadata; // ReplicatedFieldMetadata: base metadata with replication info
import org.terasology.engine.reflection.metadata.InaccessibleFieldException; // InaccessibleFieldException: thrown if field cannot be accessed

import org.terasology.engine.reflection.copy.CopyStrategy;       // CopyStrategy: strategy interface for deep-copying field values
import org.terasology.engine.reflection.copy.CopyStrategyLibrary; // CopyStrategyLibrary: registry of CopyStrategy instances per type

import org.terasology.engine.reflection.ReflectFactory;         // ReflectFactory: factory for reflection-based construction/access
import org.terasology.engine.utilities.ReflectionUtil;          // ReflectionUtil: helper to extract generic type parameters from Type

/**
 * Field Metadata for the fields of components. In addition to the standard and
 * replication metadata, has information on whether the field declares ownership
 * over an entity.
 */
public class ComponentFieldMetadata<T extends Component, U> extends ReplicatedFieldMetadata<T, U> {

    private final boolean ownedReference;
    private final CopyStrategy<U> copyWithOwnedEntitiesStrategy;

    public ComponentFieldMetadata(ClassMetadata<T, ?> owner,
                                  Field field,
                                  CopyStrategyLibrary copyStrategyLibrary,
                                  ReflectFactory factory,
                                  boolean replicatedByDefault)
            throws InaccessibleFieldException {

        super(owner, field, copyStrategyLibrary, factory, replicatedByDefault);

        // A field is considered an "owned reference" if:
        //  - it is annotated with @Owns, AND
        //  - its type is either EntityRef or a collection/map whose element/value type is EntityRef.
        ownedReference =
                field.getAnnotation(Owns.class) != null
                        && (EntityRef.class.isAssignableFrom(field.getType())
                        || isCollectionOf(EntityRef.class, field.getGenericType()));

        if (ownedReference) {
            // When the field owns entities, build a CopyStrategy that:
            //  - copies the value itself (e.g., Vector3f, lists/maps)
            //  - AND also deep-copies any contained EntityRef using EntityCopyStrategy.
            copyWithOwnedEntitiesStrategy =
                    (CopyStrategy<U>) copyStrategyLibrary
                            .createCopyOfLibraryWithStrategy(EntityRef.class, EntityCopyStrategy.INSTANCE)
                            .getStrategy(field.getGenericType());
        } else {
            // Otherwise, reuse the default copyStrategy defined in the superclass.
            copyWithOwnedEntitiesStrategy = copyStrategy;
        }
    }

    /**
     * @return Whether this field is marked with the @Owns annotation
     */
    public boolean isOwnedReference() {
        return ownedReference;
    }

    /**
     * Helper to check if this metadata's field type is a Collection or Map that
     * uses the given target type as generic element/value type.
     */
    private boolean isCollectionOf(Class<?> targetType, Type genericType) {
        return (Collection.class.isAssignableFrom(getType())
                && ReflectionUtil.getTypeParameter(genericType, 0).equals(targetType))
                || (Map.class.isAssignableFrom(getType())
                && ReflectionUtil.getTypeParameter(genericType, 1).equals(targetType));
    }

    /**
     * For types that need to be copied (e.g. Vector3f) for safe usage,
     * this method will create a new copy of a field from an object, and if the
     * field is marked @Owns, any EntityRefs in the value are copied too.
     * Otherwise it behaves the same as getValue.
     *
     * @param from The object to copy the field from
     * @return A safe to use copy of the value of this field in the given object
     */
    public U getCopyOfValueWithOwnedEntities(Object from) {
        U value = getValue(from);
        return (value != null) ? copyWithOwnedEntitiesStrategy.copy(value) : null;
    }

    /**
     * For types that need to be copied (e.g. Vector3f) for safe usage,
     * this method will create a new copy of a field from an object, and if the
     * field is marked @Owns, any EntityRefs in the value are copied too.
     * Otherwise it behaves the same as getValue.
     * This method is checked to conform to the generic parameters of the FieldMetadata.
     *
     * @param from The object to copy the field from
     * @return A safe to use copy of the value of this field in the given object
     */
    public U getCopyOfValueWithOwnedEntitiesChecked(T from) {
        return getCopyOfValueWithOwnedEntities(from);
    }
}
