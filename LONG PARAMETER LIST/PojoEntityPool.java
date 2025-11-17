public class PojoEntityPool implements EngineEntityPool {
  private static final Logger logger=LoggerFactory.getLogger(PojoEntityPool.class);
  private PojoEntityManager entityManager;
  private Map<Long,BaseEntityRef> entityStore=new MapMaker().weakValues().concurrencyLevel(4).initialCapacity(1000).makeMap();
  private ComponentTable componentStore=new ComponentTable();
  public PojoEntityPool(  PojoEntityManager entityManager){
    this.entityManager=entityManager;
  }
  @Override public void clear(){
    for (    EntityRef entity : entityStore.values()) {
      entity.invalidate();
    }
    componentStore.clear();
    entityStore.clear();
  }
  @Override public EntityRef create(){
    return create((Prefab)null,(Vector3fc)null,null);
  }
  @Override public EntityRef create(  Component... components){
    return create(Arrays.asList(components));
  }
  @Override public EntityRef create(  Iterable<Component> components){
    return create(components,true);
  }
  @Override public EntityRef create(  Iterable<Component> components,  boolean sendLifecycleEvents){
    EntityBuilder builder=newBuilder();
    builder.addComponents(components);
    builder.setSendLifecycleEvents(sendLifecycleEvents);
    return builder.build();
  }
  @Override public EntityRef create(  String prefabName){
    return create(prefabName,null,null);
  }
  @Override public EntityRef create(  String prefabName,  Vector3fc position){
    return create(prefabName,position,null);
  }
  @Override public EntityRef create(  Prefab prefab,  Vector3fc position){
    return create(prefab,position,null);
  }
  @Override public EntityRef create(  Prefab prefab){
    return create(prefab,(Vector3fc)null,null);
  }
  @Override public EntityRef create(  Prefab prefab,  Vector3fc position,  Quaternionfc rotation){
    return create(prefab,position,rotation,true);
  }
  private EntityRef createInternal(Prefab prefab, String prefabName, Vector3fc position, Quaternionfc rotation, boolean sendLifecycleEvents) {
    Prefab resolvedPrefab = prefab;
    if (resolvedPrefab == null && prefabName != null && !prefabName.isEmpty()) {
        resolvedPrefab = entityManager.getPrefabManager().getPrefab(prefabName);
        if (resolvedPrefab == null) {
            logger.warn("Unable to instantiate unknown prefab: \"{}\"", prefabName);
            return EntityRef.NULL;
        }
    }
    EntityBuilder builder = newBuilder(resolvedPrefab);
    builder.setSendLifecycleEvents(sendLifecycleEvents);
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

  private EntityRef create(Prefab prefab, Vector3fc position, Quaternionfc rotation) {
      return createInternal(prefab, null, position, rotation, true);
  }

  private EntityRef create(String prefabName, Vector3fc position, Quaternionfc rotation) {
      return createInternal(null, prefabName, position, rotation, true);
  }

  /** 
 * Destroys this entity, sending event
 * @param entityId the id of the entity to destroy
 */
  @Override public void destroy(  long entityId){
    if (!entityManager.idLoaded(entityId)) {
      return;
    }
    EntityRef ref=getEntity(entityId);
    EventSystem eventSystem=entityManager.getEventSystem();
    if (eventSystem != null) {
      eventSystem.send(ref,BeforeDeactivateComponent.newInstance());
      eventSystem.send(ref,BeforeRemoveComponent.newInstance());
    }
    entityManager.notifyComponentRemovalAndEntityDestruction(entityId,ref);
    destroy(ref);
  }
  private void destroy(  EntityRef ref){
    long entityId=ref.getId();
    entityStore.remove(entityId);
    entityManager.unregister(entityId);
    ref.invalidate();
    componentStore.remove(entityId);
  }
  /** 
 * Creates the entity without sending any events. The entity life cycle subscriber will however be informed.
 */
  @Override public EntityRef createEntityWithoutLifecycleEvents(  Iterable<Component> components){
    return create(components,false);
  }
  /** 
 * Creates the entity without sending any events. The entity life cycle subscriber will however be informed.
 */
  @Override public EntityRef createEntityWithoutLifecycleEvents(  String prefabName){
    return create(prefabName,null,null,false);
  }
  /** 
 * Creates the entity without sending any events. The entity life cycle subscriber will however be informed.
 */
  @Override public EntityRef createEntityWithoutLifecycleEvents(  Prefab prefab){
    return create(prefab,null,null,false);
  }
  /** 
 * Destroys the entity without sending any events. The entity life cycle subscriber will however be informed.
 */
  @Override public void destroyEntityWithoutEvents(  EntityRef entity){
    if (entity.isActive()) {
      entityManager.notifyComponentRemovalAndEntityDestruction(entity.getId(),entity);
      destroy(entity);
    }
  }
  @Override public EntityRef createEntityWithId(  long id,  Iterable<Component> components){
    EntityBuilder builder=newBuilder();
    builder.setId(id);
    builder.addComponents(components);
    return builder.build();
  }
  @Override public EntityBuilder newBuilder(){
    return new EntityBuilder(entityManager,this);
  }
  @Override public EntityBuilder newBuilder(  String prefabName){
    EntityBuilder builder=newBuilder();
    if (!builder.addPrefab(prefabName)) {
      logger.warn("Unable to instantiate unknown prefab: \"{}\"",prefabName);
    }
    return builder;
  }
  @Override public EntityBuilder newBuilder(  Prefab prefab){
    EntityBuilder builder=newBuilder();
    builder.addPrefab(prefab);
    return builder;
  }
  /** 
 * Gets the internal entity store. <p> It is returned as an unmodifiable map, so cannot be edited. Use  {@link #putEntity} to modify the map.
 * @return an unmodifiable version of the internal entity store
 */
  protected Map<Long,BaseEntityRef> getEntityStore(){
    return Collections.unmodifiableMap(entityStore);
  }
  /** 
 * Puts an entity into the internal storage. <p> This is intended for use by the  {@link PojoEntityManager}. In most cases, it is better to use the  {@link #create} or {@link #newBuilder} methods instead.
 * @param entityId the id of the entity to add
 * @param ref the {@link BaseEntityRef} to add
 */
  @Override public void putEntity(  long entityId,  BaseEntityRef ref){
    entityStore.put(entityId,ref);
  }
  @Override public ComponentTable getComponentStore(){
    return componentStore;
  }
  @Override public EntityRef getEntity(  long entityId){
    if (entityId == NULL_ID || !entityManager.isExistingEntity(entityId)) {
      return EntityRef.NULL;
    }
    EntityRef existing=entityStore.get(entityId);
    if (existing != EntityRef.NULL && existing != null) {
      return existing;
    }
    BaseEntityRef entity=entityManager.getEntityRefStrategy().createRefFor(entityId,entityManager);
    entityStore.put(entityId,entity);
    entityManager.assignToPool(entityId,this);
    return entity;
  }
  @SafeVarargs @Override public final Iterable<EntityRef> getEntitiesWith(  Class<? extends Component>... componentClasses){
    return () -> entityStore.keySet().stream().filter(id -> {
      for (      Class<? extends Component> component : componentClasses) {
        if (componentStore.get(id,component) == null) {
          return false;
        }
      }
      return true;
    }
    ).map(id -> getEntity(id)).iterator();
  }
  @Override public int getCountOfEntitiesWith(  Class<? extends Component>[] componentClasses){
switch (componentClasses.length) {
case 0:
      return componentStore.numEntities();
case 1:
    return componentStore.getComponentCount(componentClasses[0]);
default :
  return Lists.newArrayList(getEntitiesWith(componentClasses)).size();
}
}
  @Override public int getActiveEntityCount(){
  return entityStore.size();
  }
  @Override public Iterable<EntityRef> getAllEntities(){
  return () -> new EntityIterator(componentStore.entityIdIterator(),this);
  }
  @Override public boolean hasComponent(long entityId,Class<? extends Component> componentClass){
  return componentStore.get(entityId,componentClass) != null;
  }
  @Override public Optional<BaseEntityRef> remove(long id){
  componentStore.remove(id);
  entityManager.unassignPool(id);
  return Optional.of(entityStore.remove(id));
  }
  @Override public void insertRef(BaseEntityRef ref,Iterable<Component> components){
  entityStore.put(ref.getId(),ref);
  components.forEach(comp -> componentStore.put(ref.getId(),comp));
  entityManager.assignToPool(ref.getId(),this);
  }
  @Override public boolean contains(long id){
  return entityStore.containsKey(id);
  }
}
