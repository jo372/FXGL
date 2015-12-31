/*
 * The MIT License (MIT)
 *
 * FXGL - JavaFX Game Library
 *
 * Copyright (c) 2015 AlmasB (almaslvl@gmail.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.almasb.fxgl.physics;

import com.almasb.fxgl.app.GameApplication;
import com.almasb.fxgl.app.ServiceType;
import com.almasb.fxgl.effect.ParticleEntity;
import com.almasb.fxgl.entity.Entity;
import com.almasb.fxgl.entity.EntityType;
import com.almasb.fxgl.event.EventBus;
import com.almasb.fxgl.event.UpdateEvent;
import com.almasb.fxgl.event.WorldEvent;
import com.almasb.fxgl.util.FXGLLogger;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.geometry.Point2D;
import javafx.scene.paint.Color;
import org.jbox2d.callbacks.ContactImpulse;
import org.jbox2d.callbacks.ContactListener;
import org.jbox2d.callbacks.RayCastCallback;
import org.jbox2d.collision.Manifold;
import org.jbox2d.collision.shapes.PolygonShape;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.Fixture;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.contacts.Contact;
import org.jbox2d.particle.*;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages physics entities, collision handling and performs the physics tick
 * <p>
 * Contains several static and instance methods
 * to convert pixels coordinates to meters and vice versa
 * <p>
 * Collision handling unifies how they are processed
 *
 * @author Almas Baimagambetov (AlmasB) (almaslvl@gmail.com)
 */
@Singleton
public final class PhysicsWorld {

    private static final Logger log = FXGLLogger.getLogger("FXGL.PhysicsWorld");

    private static final float TIME_STEP = 1 / 60.0f;

    private World physicsWorld = new World(new Vec2(0, -10));

    private ParticleSystem particleSystem = physicsWorld.getParticleSystem();
    private PhysicsParticleEntity physicsParticles = new PhysicsParticleEntity();

    private List<Entity> entities = new ArrayList<>();

    private List<CollisionHandler> collisionHandlers = new ArrayList<>();

    private Map<CollisionPair, Long> collisions = new HashMap<>();

    private LongProperty tick = new SimpleLongProperty(0);

    private double appHeight;

    @Inject
    private PhysicsWorld(@Named("appHeight") double appHeight) {
        this.appHeight = appHeight;
        this.tick.bind(GameApplication.getService(ServiceType.MASTER_TIMER).tickProperty());

        physicsWorld.setContactListener(new ContactListener() {
            @Override
            public void beginContact(Contact contact) {
                PhysicsEntity e1 = (PhysicsEntity) contact.getFixtureA().getBody().getUserData();
                PhysicsEntity e2 = (PhysicsEntity) contact.getFixtureB().getBody().getUserData();

                if (!e1.isCollidable() || !e2.isCollidable())
                    return;

                int index = collisionHandlers.indexOf(new Pair<>(e1.getEntityType(), e2.getEntityType()));
                if (index != -1) {
                    CollisionPair pair = new CollisionPair(e1, e2, collisionHandlers.get(index));

                    if (!collisions.containsKey(pair)) {
                        collisions.put(pair, tick.get());
                    }
                }
            }

            @Override
            public void endContact(Contact contact) {
                PhysicsEntity e1 = (PhysicsEntity) contact.getFixtureA().getBody().getUserData();
                PhysicsEntity e2 = (PhysicsEntity) contact.getFixtureB().getBody().getUserData();

                if (!e1.isCollidable() || !e2.isCollidable())
                    return;

                int index = collisionHandlers.indexOf(new Pair<>(e1.getEntityType(), e2.getEntityType()));
                if (index != -1) {
                    CollisionPair pair = new CollisionPair(e1, e2, collisionHandlers.get(index));

                    if (collisions.containsKey(pair)) {
                        collisions.put(pair, -1L);
                    }
                }
            }

            @Override
            public void preSolve(Contact contact, Manifold oldManifold) {
            }

            @Override
            public void postSolve(Contact contact, ContactImpulse impulse) {
            }
        });

        initParticles();

        EventBus bus = GameApplication.getService(ServiceType.EVENT_BUS);
        bus.addEventHandler(WorldEvent.ENTITY_ADDED, event -> {
            addEntity(event.getEntity());
        });
        bus.addEventHandler(WorldEvent.ENTITY_REMOVED, event -> {
            removeEntity(event.getEntity());
        });
        bus.addEventHandler(UpdateEvent.ANY, event -> update());

        log.finer("Physics world initialized");
    }

    public void testAdd() {
        // TODO: do we allow to mock things like that?
        // TODO: add same for clean
        GameApplication.getService(ServiceType.EVENT_BUS)
                .fireEvent(WorldEvent.entityAdded(physicsParticles));
    }

    // TODO: allow users to set these
    private void initParticles() {
        physicsWorld.setParticleGravityScale(0.4f);
        physicsWorld.setParticleDensity(1.2f);
        physicsWorld.setParticleRadius(toMeters(1));
    }

    /**
     * Perform collision detection for all entities that have
     * setCollidable(true) and if at least one entity is not PhysicsEntity.
     * Subsequently fire collision handlers for all entities that have
     * setCollidable(true).
     */
    private void processCollisions() {
        List<Entity> collidables = entities.stream()
                .filter(Entity::isCollidable)
                .collect(Collectors.toList());

        for (int i = 0; i < collidables.size(); i++) {
            Entity e1 = collidables.get(i);

            for (int j = i + 1; j < collidables.size(); j++) {
                Entity e2 = collidables.get(j);

                if (e1 instanceof PhysicsEntity && e2 instanceof PhysicsEntity) {
                    PhysicsEntity p1 = (PhysicsEntity) e1;
                    PhysicsEntity p2 = (PhysicsEntity) e2;
                    boolean skip = true;
                    if ((p1.body.getType() == BodyType.KINEMATIC && p2.body.getType() == BodyType.STATIC)
                            || (p2.body.getType() == BodyType.KINEMATIC && p1.body.getType() == BodyType.STATIC)) {
                        skip = false;
                    }
                    if (skip)
                        continue;
                }

                int index = collisionHandlers.indexOf(new Pair<>(e1.getEntityType(), e2.getEntityType()));
                if (index != -1) {
                    CollisionPair pair = new CollisionPair(e1, e2, collisionHandlers.get(index));

                    CollisionResult result = e1.checkCollision(e2);

                    if (result.hasCollided()) {
                        if (!collisions.containsKey(pair)) {
                            collisions.put(pair, tick.get());
                            pair.getHandler().onHitBoxTrigger(pair.getA(), pair.getB(), result.getBoxA(), result.getBoxB());
                        }
                    } else {
                        if (collisions.containsKey(pair)) {
                            collisions.put(pair, -1L);
                        }
                    }
                }
            }
        }

        List<CollisionPair> toRemove = new ArrayList<>();
        collisions.forEach((pair, cachedTick) -> {
            if (!pair.getA().isActive() || !pair.getB().isActive()
                    || !pair.getA().isCollidable() || !pair.getB().isCollidable()) {
                toRemove.add(pair);
                return;
            }

            if (cachedTick == -1L) {
                pair.getHandler().onCollisionEnd(pair.getA(), pair.getB());
                toRemove.add(pair);
            } else if (tick.get() == cachedTick) {
                pair.getHandler().onCollisionBegin(pair.getA(), pair.getB());
            } else if (tick.get() > cachedTick) {
                pair.getHandler().onCollision(pair.getA(), pair.getB());
            }
        });

        toRemove.forEach(collisions::remove);
    }

    private void updateParticles() {
        List<PhysicsParticle> localParticles = new ArrayList<>();

        int count = particleSystem.getParticleCount();
        if (count != 0) {
            float radius = particleSystem.getParticleRadius();
            Vec2[] positionBuffer = particleSystem.getParticlePositionBuffer();
            Object[] colors = particleSystem.getParticleUserDataBuffer();

            for (int i = 0; i < count; i++) {
                Vec2 v = positionBuffer[i];

                double x = Math.round(toPixels(v.x - radius));
                double y = Math.round(toPixels(toMeters(appHeight) - v.y - radius));

                Color color = (Color) colors[i];
                localParticles.add(new PhysicsParticle(new Point2D(x, y), toPixels(radius), color));
            }
        }

        physicsParticles.setAll(localParticles);
    }

    /**
     * Registers a collision handler.
     * The order in which the types are passed to this method
     * decides the order of objects being passed into the collision handler
     * <p>
     * <pre>
     * Example:
     * PhysicsWorld physics = ...
     * physics.addCollisionHandler(new CollisionHandler(Type.PLAYER, Type.ENEMY) {
     *      public void onCollisionBegin(Entity a, Entity b) {
     *          // called when entities start touching
     *      }
     *      public void onCollision(Entity a, Entity b) {
     *          // called when entities are touching
     *      }
     *      public void onCollisionEnd(Entity a, Entity b) {
     *          // called when entities are separated and no longer touching
     *      }
     * });
     *
     * </pre>
     *
     * @param handler collision handler
     */
    public void addCollisionHandler(CollisionHandler handler) {
        collisionHandlers.add(handler);
    }

    /**
     * Removes a collision handler
     *
     * @param handler collision handler to remove
     */
    public void removeCollisionHandler(CollisionHandler handler) {
        collisionHandlers.remove(handler);
    }

    private void addEntity(Entity entity) {
        entities.add(entity);
        if (entity instanceof PhysicsEntity) {
            PhysicsEntity pEntity = (PhysicsEntity) entity;
            createBody(pEntity);
            pEntity.onInitPhysics();
        }
    }

    private void removeEntity(Entity entity) {
        entities.remove(entity);
        if (entity instanceof PhysicsEntity)
            destroyBody((PhysicsEntity) entity);
    }

    private void update() {
        physicsWorld.step(TIME_STEP, 8, 3);

        processCollisions();

        for (Body body = physicsWorld.getBodyList(); body != null; body = body.getNext()) {
            Entity e = (Entity) body.getUserData();
            e.setX(
                    Math.round(toPixels(
                            body.getPosition().x
                                    - toMeters(e.getWidth() / 2))));
            e.setY(
                    Math.round(toPixels(
                            toMeters(appHeight) - body.getPosition().y
                                    - toMeters(e.getHeight() / 2))));
            e.setRotation(-Math.toDegrees(body.getAngle()));
        }

        updateParticles();
    }

    public void setGravity(double x, double y) {
        physicsWorld.setGravity(new Vec2().addLocal((float) x, -(float) y));
    }

    /**
     * Create physics body and attach to physics world.
     *
     * @param e physics entity
     */
    private void createBody(PhysicsEntity e) {
        double x = e.getX(),
                y = e.getY(),
                w = e.getWidth(),
                h = e.getHeight();

        if (e.fixtureDef.shape == null) {
            PolygonShape rectShape = new PolygonShape();
            rectShape.setAsBox(toMeters(w / 2), toMeters(h / 2));
            e.fixtureDef.shape = rectShape;
        }

        e.bodyDef.position.set(toMeters(x + w / 2), toMeters(appHeight - (y + h / 2)));
        e.body = physicsWorld.createBody(e.bodyDef);
        e.fixture = e.body.createFixture(e.fixtureDef);
        e.body.setUserData(e);
    }

    /**
     * Destroy body and remove from physics world.
     *
     * @param e physics entity
     */
    private void destroyBody(PhysicsEntity e) {
        physicsWorld.destroyBody(e.body);
    }

    private EdgeCallback raycastCallback = new EdgeCallback();

    /**
     * Performs a ray cast from start point to end point.
     *
     * @param start start point
     * @param end end point
     * @return ray cast result
     */
    public RaycastResult raycast(Point2D start, Point2D end) {
        raycastCallback.reset();
        physicsWorld.raycast(raycastCallback, toPoint(start), toPoint(end));

        PhysicsEntity entity = null;
        Point2D point = null;

        if (raycastCallback.fixture != null)
            entity = (PhysicsEntity) raycastCallback.fixture.getBody().getUserData();

        if (raycastCallback.point != null)
            point = toPoint(raycastCallback.point);

        return new RaycastResult(Optional.ofNullable(entity), Optional.ofNullable(point));
    }

    /**
     * Converts pixels to meters
     *
     * @param pixels value in pixels
     * @return value in meters
     */
    public static float toMeters(double pixels) {
        return (float) pixels * 0.05f;
    }

    /**
     * Converts meters to pixels
     *
     * @param meters value in meters
     * @return value in pixels
     */
    public static float toPixels(double meters) {
        return (float) meters * 20f;
    }

    /**
     * Converts a vector of type Point2D to vector of type Vec2
     *
     * @param v vector in pixels
     * @return vector in meters
     */
    public static Vec2 toVector(Point2D v) {
        return new Vec2(toMeters(v.getX()), toMeters(-v.getY()));
    }

    /**
     * Converts a vector of type Vec2 to vector of type Point2D
     *
     * @param v vector in meters
     * @return vector in pixels
     */
    public static Point2D toVector(Vec2 v) {
        return new Point2D(toPixels(v.x), toPixels(-v.y));
    }

    public Vec2 toPoint(Point2D p) {
        return new Vec2(toMeters(p.getX()), toMeters(appHeight - p.getY()));
    }

    public Point2D toPoint(Vec2 p) {
        return new Point2D(toPixels(p.x), toPixels(toMeters(appHeight) - p.y));
    }

    // TODO: remove when done
    public World getWorld() {
        return physicsWorld;
    }

    // we return reference so that it can be cleaned up by physics world
    public ParticleGroup createLiquid(double x, double y, double width, double height, Color color) {
        PolygonShape shape = new PolygonShape();
        shape.setAsBox(toMeters(width / 2), toMeters(height / 2));

        ParticleGroupDef pd = new ParticleGroupDef();
        pd.position.set(toMeters(x + width / 2), toMeters(appHeight - (y + height / 2)));

        // TODO: allow users to choose type of particle
        //pd.flags = ParticleType.b2_tensileParticle | ParticleType.b2_viscousParticle;
        pd.flags = ParticleType.b2_waterParticle;
        pd.userData = color;
        pd.shape = shape;
        ParticleGroup particleGroup = physicsWorld.createParticleGroup(pd);
        return particleGroup;
    }

    private static class EdgeCallback implements RayCastCallback {
        Fixture fixture;
        Vec2 point;
        //Vec2 normal;
        float bestFraction = 1.0f;

        @Override
        public float reportFixture(Fixture fixture, Vec2 point, Vec2 normal, float fraction) {
            PhysicsEntity e = (PhysicsEntity) fixture.getBody().getUserData();
            if (e.isRaycastIgnored())
                return 1;

            if (fraction < bestFraction) {
                this.fixture = fixture;
                this.point = point.clone();
                //this.normal = normal.clone();
                bestFraction = fraction;
            }

            return bestFraction;
        }

        void reset() {
            fixture = null;
            point = null;
            bestFraction = 1.0f;
        }
    }

    private class PhysicsParticleEntity extends ParticleEntity {
        public PhysicsParticleEntity() {
            super(new EntityType() {
                @Override
                public String getUniqueType() {
                    return "__PHYSICS_PARTICLE_ENTITY__";
                }
            });
        }
        
        void setAll(List<PhysicsParticle> physicsParticles) {
            this.particles.clear();
            this.particles.addAll(physicsParticles);
        }
    }
}
