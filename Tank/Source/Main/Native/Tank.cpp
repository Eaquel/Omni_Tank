#include "Tank.hpp"

#include <algorithm>
#include <cmath>
#include <jni.h>
#include <mutex>

namespace omni::tank {
namespace {

constexpr float pi = 3.14159265358979f;
constexpr float two_pi = 6.28318530717959f;
constexpr float to_degrees = 57.2957795f;

constexpr float player_radius = 46.0f;
constexpr float enemy_radius = 42.0f;

constexpr std::array<float, ability_count> ability_cost{30.0f, 24.0f, 34.0f, 22.0f};
constexpr std::array<float, ability_count> ability_cooldown{12.0f, 8.0f, 18.0f, 14.0f};

constexpr std::int32_t max_upgrade_level = 8;

float wrapAngle(float angle) noexcept {
    while (angle > pi) angle -= two_pi;
    while (angle < -pi) angle += two_pi;
    return angle;
}

float approachAngle(float current, float target, float rate) noexcept {
    return current + wrapAngle(target - current) * std::min(1.0f, rate);
}

float lengthOf(Vec2 value) noexcept {
    return std::sqrt(value.x * value.x + value.y * value.y);
}

float distanceBetween(Vec2 a, Vec2 b) noexcept {
    const float dx = a.x - b.x;
    const float dy = a.y - b.y;
    return std::sqrt(dx * dx + dy * dy);
}

float experienceForLevel(std::int32_t level) noexcept {
    const float l = static_cast<float>(level);
    return 45.0f + l * l * 16.0f;
}

// Hull profiles: standard, recon, heavy.
float hullSpeed(std::int32_t hull) noexcept {
    if (hull == 1) return 1.24f;
    if (hull == 2) return 0.82f;
    return 1.0f;
}

float hullHealth(std::int32_t hull) noexcept {
    if (hull == 1) return 0.82f;
    if (hull == 2) return 1.38f;
    return 1.0f;
}

float hullDamage(std::int32_t hull) noexcept {
    if (hull == 1) return 0.86f;
    if (hull == 2) return 1.22f;
    return 1.0f;
}

std::int32_t hullBarrels(std::int32_t hull) noexcept {
    return hull == 2 ? 2 : 1;
}

}

TankSimulation::TankSimulation() noexcept {
    reset();
}

float TankSimulation::random() noexcept {
    rng_ ^= rng_ << 13;
    rng_ ^= rng_ >> 17;
    rng_ ^= rng_ << 5;
    return static_cast<float>(rng_ & 0xFFFFFFu) / 16777216.0f;
}

float TankSimulation::randomRange(float low, float high) noexcept {
    return low + (high - low) * random();
}

void TankSimulation::reset() noexcept {
    player_ = {};
    input_ = {};
    bullets_ = {};
    enemies_ = {};
    shapes_ = {};
    particles_ = {};
    upgrades_ = {};
    abilityCooldowns_ = {};

    time_ = 0.0f;
    spawnTimer_ = 1.5f;
    waveTimer_ = 0.0f;
    shieldTimer_ = 0.0f;
    overdriveTimer_ = 0.0f;
    scanTimer_ = 0.0f;
    cameraZoom_ = 1.0f;
    threat_ = 0.0f;
    wave_ = 1;
    rng_ = 0x9E3779B9u;

    player_.maxHealth = maxHealthStat();
    player_.health = player_.maxHealth;
    player_.energy = player_.maxEnergy;
    player_.alive = true;

    spawnWorld();
}

void TankSimulation::spawnWorld() noexcept {
    for (std::size_t i = 0; i < shapes_.size(); ++i) {
        spawnShape(i, true);
    }
}

void TankSimulation::spawnShape(std::size_t index, bool anywhere) noexcept {
    Shape& shape = shapes_[index];
    const float roll = random();

    if (roll > 0.94f) {
        shape.sides = 5;
        shape.size = randomRange(58.0f, 72.0f);
        shape.maxHealth = 190.0f;
    } else if (roll > 0.68f) {
        shape.sides = 3;
        shape.size = randomRange(36.0f, 46.0f);
        shape.maxHealth = 46.0f;
    } else {
        shape.sides = 4;
        shape.size = randomRange(26.0f, 36.0f);
        shape.maxHealth = 22.0f;
    }

    if (mode_ == 4) shape.maxHealth *= 1.45f;

    shape.health = shape.maxHealth;
    shape.rotation = randomRange(0.0f, two_pi);
    shape.spin = randomRange(-0.55f, 0.55f);
    shape.velocity = {randomRange(-16.0f, 16.0f), randomRange(-16.0f, 16.0f)};
    shape.flash = 0.0f;
    shape.active = true;

    if (anywhere) {
        shape.position = {randomRange(-arenaHalfWidth_, arenaHalfWidth_),
                          randomRange(-arenaHalfHeight_, arenaHalfHeight_)};
        if (distanceBetween(shape.position, player_.position) < 320.0f) {
            shape.position.x += 420.0f;
        }
    } else {
        const float angle = randomRange(0.0f, two_pi);
        const float radius = randomRange(1300.0f, 2000.0f);
        shape.position = {player_.position.x + std::cos(angle) * radius,
                          player_.position.y + std::sin(angle) * radius};
    }

    clampToArena(shape.position, shape.size);
}

void TankSimulation::spawnEnemy() noexcept {
    for (Enemy& enemy : enemies_) {
        if (enemy.active) continue;

        const float roll = random();
        if (roll > 0.82f) {
            enemy.kind = 2;
            enemy.maxHealth = 130.0f;
            enemy.scale = 1.32f;
        } else if (roll > 0.52f) {
            enemy.kind = 1;
            enemy.maxHealth = 62.0f;
            enemy.scale = 0.92f;
        } else {
            enemy.kind = 0;
            enemy.maxHealth = 84.0f;
            enemy.scale = 1.0f;
        }

        enemy.maxHealth *= 1.0f + static_cast<float>(wave_ - 1) * 0.16f;
        if (mode_ == 1) enemy.maxHealth *= 1.18f;
        if (mode_ == 3) enemy.maxHealth *= 0.6f;

        const float angle = randomRange(0.0f, two_pi);
        const float radius = randomRange(1500.0f, 2100.0f);
        enemy.position = {player_.position.x + std::cos(angle) * radius,
                          player_.position.y + std::sin(angle) * radius};
        clampToArena(enemy.position, enemy_radius * enemy.scale);

        enemy.velocity = {};
        enemy.health = enemy.maxHealth;
        enemy.heading = angle + pi;
        enemy.turret = enemy.heading;
        enemy.reload = randomRange(0.4f, 1.4f);
        enemy.wander = randomRange(0.0f, two_pi);
        enemy.flash = 0.0f;
        enemy.active = true;
        burst(enemy.position, 2, 8, 150.0f);
        return;
    }
}

void TankSimulation::fireBullet(Vec2 origin, float angle, float speed, float damage,
                                float radius, float life, std::int32_t owner) noexcept {
    for (Bullet& bullet : bullets_) {
        if (bullet.active) continue;

        bullet.position = origin;
        bullet.velocity = {std::cos(angle) * speed, std::sin(angle) * speed};
        bullet.radius = radius;
        bullet.life = life;
        bullet.maxLife = life;
        bullet.damage = damage;
        bullet.owner = owner;
        bullet.active = true;
        return;
    }
}

void TankSimulation::burst(Vec2 origin, std::int32_t kind, std::int32_t count, float speed) noexcept {
    if (graphicsQuality_ <= 0) return;

    const std::int32_t scaled =
        std::max(1, count * std::min(graphicsQuality_ + 1, 4) / 4);

    std::int32_t spawned = 0;
    for (Particle& particle : particles_) {
        if (spawned >= scaled) return;
        if (particle.active) continue;

        const float angle = randomRange(0.0f, two_pi);
        const float magnitude = randomRange(speed * 0.35f, speed);
        particle.position = origin;
        particle.velocity = {std::cos(angle) * magnitude, std::sin(angle) * magnitude};
        particle.maxLife = randomRange(0.32f, 0.85f);
        particle.life = particle.maxLife;
        particle.size = randomRange(3.0f, 9.0f);
        particle.kind = kind;
        particle.active = true;
        ++spawned;
    }
}

void TankSimulation::clampToArena(Vec2& position, float radius) const noexcept {
    position.x = std::clamp(position.x, -arenaHalfWidth_ + radius, arenaHalfWidth_ - radius);
    position.y = std::clamp(position.y, -arenaHalfHeight_ + radius, arenaHalfHeight_ - radius);
}

float TankSimulation::damageStat() const noexcept {
    return 15.0f * (1.0f + static_cast<float>(upgrades_[0]) * 0.17f) * hullDamage(hull_);
}

float TankSimulation::reloadStat() const noexcept {
    const float boost = overdriveTimer_ > 0.0f ? 1.85f : 1.0f;
    return 2.7f * (1.0f + static_cast<float>(upgrades_[1]) * 0.15f) * boost;
}

float TankSimulation::bulletSpeedStat() const noexcept {
    const float hullBonus = hull_ == 1 ? 1.16f : 1.0f;
    return 820.0f * (1.0f + static_cast<float>(upgrades_[2]) * 0.09f) * hullBonus;
}

float TankSimulation::moveSpeedStat() const noexcept {
    const float boost = overdriveTimer_ > 0.0f ? 1.55f : 1.0f;
    return 315.0f * (1.0f + static_cast<float>(upgrades_[3]) * 0.085f) * hullSpeed(hull_) * boost;
}

float TankSimulation::maxHealthStat() const noexcept {
    return 100.0f * (1.0f + static_cast<float>(upgrades_[4]) * 0.19f) * hullHealth(hull_);
}

float TankSimulation::regenStat() const noexcept {
    return 1.6f + static_cast<float>(upgrades_[5]) * 1.9f;
}

void TankSimulation::setMode(std::int32_t mode) noexcept {
    mode_ = std::clamp(mode, 0, 4);
}

void TankSimulation::setHull(std::int32_t hull) noexcept {
    hull_ = std::clamp(hull, 0, 2);
    const float ratio = player_.maxHealth > 0.0f ? player_.health / player_.maxHealth : 1.0f;
    player_.maxHealth = maxHealthStat();
    player_.health = player_.maxHealth * std::clamp(ratio, 0.0f, 1.0f);
}

void TankSimulation::setGraphicsQuality(std::int32_t level) noexcept {
    graphicsQuality_ = std::clamp(level, 0, 4);
}

void TankSimulation::setDeveloperFlag(std::int32_t flag, bool enabled) noexcept {
    if (flag < 0 || flag >= static_cast<std::int32_t>(developerFlags_.size())) return;
    developerFlags_[static_cast<std::size_t>(flag)] = enabled;
}

void TankSimulation::setInput(float moveX, float moveY, float aimX, float aimY, bool firing) noexcept {
    input_.moveX = std::clamp(moveX, -1.0f, 1.0f);
    input_.moveY = std::clamp(moveY, -1.0f, 1.0f);
    input_.aimX = std::clamp(aimX, -1.0f, 1.0f);
    input_.aimY = std::clamp(aimY, -1.0f, 1.0f);
    input_.firing = firing;
}

bool TankSimulation::triggerAbility(std::int32_t index) noexcept {
    if (index < 0 || index >= static_cast<std::int32_t>(ability_count)) return false;

    const std::size_t slot = static_cast<std::size_t>(index);
    if (abilityCooldowns_[slot] > 0.0f) return false;

    const float cost = developerFlags_[1] ? 0.0f : ability_cost[slot];
    if (player_.energy < cost) return false;

    player_.energy -= cost;
    abilityCooldowns_[slot] = ability_cooldown[slot];

    switch (index) {
        case 0:
            shieldTimer_ = 4.5f;
            burst(player_.position, 1, 26, 260.0f);
            break;
        case 1:
            overdriveTimer_ = 6.0f;
            burst(player_.position, 3, 22, 300.0f);
            break;
        case 2:
            player_.health = std::min(player_.maxHealth, player_.health + player_.maxHealth * 0.45f);
            burst(player_.position, 4, 30, 220.0f);
            break;
        default:
            scanTimer_ = 6.5f;
            burst(player_.position, 5, 34, 380.0f);
            break;
    }

    return true;
}

bool TankSimulation::upgrade(std::int32_t stat) noexcept {
    if (stat < 0 || stat >= static_cast<std::int32_t>(upgrade_count)) return false;

    const std::size_t slot = static_cast<std::size_t>(stat);
    if (player_.statPoints <= 0 || upgrades_[slot] >= max_upgrade_level) return false;

    ++upgrades_[slot];
    --player_.statPoints;

    const float ratio = player_.maxHealth > 0.0f ? player_.health / player_.maxHealth : 1.0f;
    player_.maxHealth = maxHealthStat();
    player_.health = std::min(player_.maxHealth, player_.maxHealth * ratio + 12.0f);
    return true;
}

void TankSimulation::respawn() noexcept {
    player_.position = {};
    player_.velocity = {};
    player_.maxHealth = maxHealthStat();
    player_.health = player_.maxHealth;
    player_.energy = player_.maxEnergy;
    player_.respawnTimer = 0.0f;
    player_.damageFlash = 0.0f;
    player_.alive = true;
    shieldTimer_ = 2.5f;

    for (Enemy& enemy : enemies_) {
        if (enemy.active && distanceBetween(enemy.position, player_.position) < 900.0f) {
            enemy.active = false;
        }
    }
    for (Bullet& bullet : bullets_) {
        if (bullet.owner == 1) bullet.active = false;
    }

    burst(player_.position, 1, 40, 420.0f);
}

std::int32_t TankSimulation::upgradeLevel(std::int32_t stat) const noexcept {
    if (stat < 0 || stat >= static_cast<std::int32_t>(upgrade_count)) return 0;
    return upgrades_[static_cast<std::size_t>(stat)];
}

std::int32_t TankSimulation::statPoints() const noexcept {
    return player_.statPoints;
}

std::int32_t TankSimulation::level() const noexcept {
    return player_.level;
}

float TankSimulation::abilityCooldownRatio(std::int32_t index) const noexcept {
    if (index < 0 || index >= static_cast<std::int32_t>(ability_count)) return 1.0f;

    const std::size_t slot = static_cast<std::size_t>(index);
    if (abilityCooldowns_[slot] <= 0.0f) return 1.0f;
    return 1.0f - std::clamp(abilityCooldowns_[slot] / ability_cooldown[slot], 0.0f, 1.0f);
}

void TankSimulation::awardExperience(float amount, float points) noexcept {
    player_.xp += amount;
    player_.score += points;

    while (player_.xp >= experienceForLevel(player_.level)) {
        player_.xp -= experienceForLevel(player_.level);
        ++player_.level;
        ++player_.statPoints;
        player_.maxHealth = maxHealthStat();
        player_.health = std::min(player_.maxHealth, player_.health + player_.maxHealth * 0.22f);
        player_.energy = player_.maxEnergy;
        burst(player_.position, 4, 34, 340.0f);
    }
}

void TankSimulation::damagePlayer(float amount) noexcept {
    if (!player_.alive) return;
    if (developerFlags_[0] || shieldTimer_ > 0.0f) return;

    player_.health -= amount;
    player_.damageFlash = std::min(1.0f, player_.damageFlash + amount / 45.0f);

    if (player_.health <= 0.0f) {
        player_.health = 0.0f;
        player_.alive = false;
        player_.respawnTimer = 3.0f;
        burst(player_.position, 0, 60, 520.0f);
    }
}

void TankSimulation::updatePlayer(float dt) noexcept {
    player_.recoil = std::max(0.0f, player_.recoil - dt * 6.0f);
    player_.muzzleFlash = std::max(0.0f, player_.muzzleFlash - dt * 7.0f);
    player_.damageFlash = std::max(0.0f, player_.damageFlash - dt * 1.9f);

    for (float& cooldown : abilityCooldowns_) {
        cooldown = std::max(0.0f, cooldown - dt);
    }

    shieldTimer_ = std::max(0.0f, shieldTimer_ - dt);
    overdriveTimer_ = std::max(0.0f, overdriveTimer_ - dt);
    scanTimer_ = std::max(0.0f, scanTimer_ - dt);

    if (!player_.alive) {
        player_.respawnTimer = std::max(0.0f, player_.respawnTimer - dt);
        player_.velocity = {player_.velocity.x * 0.9f, player_.velocity.y * 0.9f};
        return;
    }

    player_.energy = std::min(player_.maxEnergy,
                              player_.energy + (developerFlags_[1] ? 100.0f : 11.0f) * dt);
    player_.health = std::min(player_.maxHealth, player_.health + regenStat() * dt);

    const Vec2 wish{input_.moveX, input_.moveY};
    const float magnitude = std::clamp(lengthOf(wish), 0.0f, 1.0f);
    const float speed = moveSpeedStat();

    if (magnitude > 0.06f) {
        const float inverse = 1.0f / std::max(0.0001f, lengthOf(wish));
        const Vec2 direction{wish.x * inverse, wish.y * inverse};
        player_.velocity.x += (direction.x * speed * magnitude - player_.velocity.x) * std::min(1.0f, dt * 7.0f);
        player_.velocity.y += (direction.y * speed * magnitude - player_.velocity.y) * std::min(1.0f, dt * 7.0f);
        player_.heading = approachAngle(player_.heading, std::atan2(direction.y, direction.x), dt * 9.0f);
    } else {
        player_.velocity.x -= player_.velocity.x * std::min(1.0f, dt * 6.0f);
        player_.velocity.y -= player_.velocity.y * std::min(1.0f, dt * 6.0f);
    }

    player_.position.x += player_.velocity.x * dt;
    player_.position.y += player_.velocity.y * dt;
    clampToArena(player_.position, player_radius);

    const float aimMagnitude = std::sqrt(input_.aimX * input_.aimX + input_.aimY * input_.aimY);
    if (aimMagnitude > 0.14f) {
        player_.turret = approachAngle(player_.turret, std::atan2(input_.aimY, input_.aimX), dt * 16.0f);
    } else if (magnitude > 0.06f) {
        player_.turret = approachAngle(player_.turret, player_.heading, dt * 4.0f);
    }

    player_.reload = std::max(0.0f, player_.reload - dt);

    const bool wantsFire = input_.firing || aimMagnitude > 0.55f;
    if (wantsFire && player_.reload <= 0.0f) {
        player_.reload = 1.0f / std::max(0.35f, reloadStat());
        player_.recoil = 1.0f;
        player_.muzzleFlash = 1.0f;

        const std::int32_t barrels = hullBarrels(hull_);
        const float damage = damageStat();
        const float bulletSpeed = bulletSpeedStat();

        for (std::int32_t i = 0; i < barrels; ++i) {
            const float offset = barrels == 1 ? 0.0f : (static_cast<float>(i) - 0.5f) * 0.09f;
            const float angle = player_.turret + offset;
            const Vec2 muzzle{player_.position.x + std::cos(angle) * 96.0f,
                              player_.position.y + std::sin(angle) * 96.0f};
            fireBullet(muzzle, angle, bulletSpeed, damage / static_cast<float>(barrels),
                       hull_ == 2 ? 9.0f : 8.0f, 1.5f, 0);
        }

        burst({player_.position.x + std::cos(player_.turret) * 96.0f,
               player_.position.y + std::sin(player_.turret) * 96.0f},
              3, 4, 130.0f);
    }

    const float targetZoom = 1.0f + static_cast<float>(player_.level - 1) * 0.012f;
    cameraZoom_ += (std::min(1.42f, targetZoom) - cameraZoom_) * std::min(1.0f, dt * 1.6f);
}

void TankSimulation::updateEnemies(float dt) noexcept {
    const float slow = scanTimer_ > 0.0f ? 0.55f : 1.0f;
    threat_ = 0.0f;

    for (Enemy& enemy : enemies_) {
        if (!enemy.active) continue;

        enemy.flash = std::max(0.0f, enemy.flash - dt * 4.0f);
        enemy.wander += dt * 0.9f;

        const float distance = distanceBetween(enemy.position, player_.position);
        const float toPlayer = std::atan2(player_.position.y - enemy.position.y,
                                          player_.position.x - enemy.position.x);

        float desired = toPlayer;
        float speed = 165.0f;

        if (enemy.kind == 1) {
            speed = 195.0f;
            if (distance < 720.0f) desired = toPlayer + pi;
            else if (distance < 980.0f) desired = toPlayer + pi * 0.5f;
        } else if (enemy.kind == 2) {
            speed = 118.0f;
        } else {
            speed = 205.0f;
        }

        speed *= slow;
        speed *= 1.0f + static_cast<float>(wave_ - 1) * 0.03f;
        if (mode_ == 3) speed *= 0.7f;
        if (!player_.alive) {
            desired = enemy.wander;
            speed *= 0.4f;
        }

        enemy.heading = approachAngle(enemy.heading, desired, dt * 3.4f);
        enemy.turret = approachAngle(enemy.turret, toPlayer, dt * 5.5f);

        const float wobble = std::sin(enemy.wander) * 0.35f;
        enemy.velocity.x += (std::cos(enemy.heading + wobble) * speed - enemy.velocity.x) * std::min(1.0f, dt * 4.0f);
        enemy.velocity.y += (std::sin(enemy.heading + wobble) * speed - enemy.velocity.y) * std::min(1.0f, dt * 4.0f);
        enemy.position.x += enemy.velocity.x * dt;
        enemy.position.y += enemy.velocity.y * dt;
        clampToArena(enemy.position, enemy_radius * enemy.scale);

        if (distance < 1600.0f) {
            threat_ = std::min(1.0f, threat_ + 0.14f);
        }

        enemy.reload = std::max(0.0f, enemy.reload - dt * slow);
        if (player_.alive && enemy.reload <= 0.0f && distance < 1500.0f) {
            const float damageScale = mode_ == 3 ? 0.45f : 1.0f;

            if (enemy.kind == 1) {
                enemy.reload = 1.55f;
                fireBullet(enemy.position, enemy.turret, 760.0f, 13.0f * damageScale, 7.0f, 2.2f, 1);
            } else if (enemy.kind == 2) {
                enemy.reload = 2.1f;
                for (std::int32_t i = -1; i <= 1; ++i) {
                    fireBullet(enemy.position, enemy.turret + static_cast<float>(i) * 0.19f,
                               520.0f, 11.0f * damageScale, 10.0f, 2.0f, 1);
                }
            } else {
                enemy.reload = 1.05f;
                fireBullet(enemy.position, enemy.turret, 620.0f, 9.0f * damageScale, 7.0f, 1.8f, 1);
            }
        }

        const float contact = player_radius + enemy_radius * enemy.scale;
        if (player_.alive && distance < contact) {
            damagePlayer(26.0f * dt * (enemy.kind == 2 ? 1.8f : 1.0f));
            const float push = (contact - distance) * 0.5f;
            enemy.position.x -= std::cos(toPlayer) * push;
            enemy.position.y -= std::sin(toPlayer) * push;
        }
    }
}

void TankSimulation::updateShapes(float dt) noexcept {
    for (std::size_t i = 0; i < shapes_.size(); ++i) {
        Shape& shape = shapes_[i];
        if (!shape.active) continue;

        shape.flash = std::max(0.0f, shape.flash - dt * 4.0f);
        shape.rotation += shape.spin * dt;
        shape.position.x += shape.velocity.x * dt;
        shape.position.y += shape.velocity.y * dt;

        if (shape.position.x < -arenaHalfWidth_ + shape.size ||
            shape.position.x > arenaHalfWidth_ - shape.size) {
            shape.velocity.x = -shape.velocity.x;
        }
        if (shape.position.y < -arenaHalfHeight_ + shape.size ||
            shape.position.y > arenaHalfHeight_ - shape.size) {
            shape.velocity.y = -shape.velocity.y;
        }
        clampToArena(shape.position, shape.size);

        if (player_.alive) {
            const float contact = player_radius + shape.size;
            const float distance = distanceBetween(shape.position, player_.position);
            if (distance < contact) {
                damagePlayer(9.0f * dt);
                shape.health -= 26.0f * dt;
                shape.flash = 1.0f;
                const float angle = std::atan2(shape.position.y - player_.position.y,
                                               shape.position.x - player_.position.x);
                shape.position.x += std::cos(angle) * (contact - distance);
                shape.position.y += std::sin(angle) * (contact - distance);

                if (shape.health <= 0.0f) {
                    shape.active = false;
                    burst(shape.position, 6, 14, 240.0f);
                    awardExperience(shape.maxHealth * 0.85f, shape.maxHealth * 0.5f);
                    spawnShape(i, false);
                }
            }
        }
    }
}

void TankSimulation::updateBullets(float dt) noexcept {
    for (Bullet& bullet : bullets_) {
        if (!bullet.active) continue;

        bullet.life -= dt;
        if (bullet.life <= 0.0f) {
            bullet.active = false;
            continue;
        }

        bullet.position.x += bullet.velocity.x * dt;
        bullet.position.y += bullet.velocity.y * dt;

        if (bullet.position.x < -arenaHalfWidth_ || bullet.position.x > arenaHalfWidth_ ||
            bullet.position.y < -arenaHalfHeight_ || bullet.position.y > arenaHalfHeight_) {
            bullet.active = false;
            burst(bullet.position, bullet.owner == 0 ? 3 : 0, 4, 120.0f);
            continue;
        }

        if (bullet.owner == 0) {
            for (Enemy& enemy : enemies_) {
                if (!enemy.active) continue;
                if (distanceBetween(bullet.position, enemy.position) >
                    bullet.radius + enemy_radius * enemy.scale) {
                    continue;
                }

                enemy.health -= bullet.damage;
                enemy.flash = 1.0f;
                bullet.active = false;
                burst(bullet.position, 3, 6, 180.0f);

                if (enemy.health <= 0.0f) {
                    enemy.active = false;
                    ++player_.kills;
                    burst(enemy.position, 0, 26, 400.0f);
                    awardExperience(72.0f + static_cast<float>(wave_) * 9.0f,
                                    120.0f + static_cast<float>(wave_) * 14.0f);
                }
                break;
            }

            if (!bullet.active) continue;

            for (std::size_t i = 0; i < shapes_.size(); ++i) {
                Shape& shape = shapes_[i];
                if (!shape.active) continue;
                if (distanceBetween(bullet.position, shape.position) > bullet.radius + shape.size) {
                    continue;
                }

                shape.health -= bullet.damage;
                shape.flash = 1.0f;
                bullet.active = false;
                burst(bullet.position, 6, 5, 160.0f);

                if (shape.health <= 0.0f) {
                    shape.active = false;
                    burst(shape.position, 6, 16, 260.0f);
                    awardExperience(shape.maxHealth * 0.85f, shape.maxHealth * 0.5f);
                    spawnShape(i, false);
                }
                break;
            }
        } else if (player_.alive &&
                   distanceBetween(bullet.position, player_.position) < bullet.radius + player_radius) {
            bullet.active = false;
            damagePlayer(bullet.damage);
            burst(bullet.position, shieldTimer_ > 0.0f ? 1 : 0, 8, 220.0f);
        }
    }
}

void TankSimulation::updateParticles(float dt) noexcept {
    for (Particle& particle : particles_) {
        if (!particle.active) continue;

        particle.life -= dt;
        if (particle.life <= 0.0f) {
            particle.active = false;
            continue;
        }

        particle.position.x += particle.velocity.x * dt;
        particle.position.y += particle.velocity.y * dt;
        particle.velocity.x -= particle.velocity.x * std::min(1.0f, dt * 2.4f);
        particle.velocity.y -= particle.velocity.y * std::min(1.0f, dt * 2.4f);
    }
}

void TankSimulation::updateSpawning(float dt) noexcept {
    waveTimer_ += dt;
    if (waveTimer_ >= 30.0f) {
        waveTimer_ = 0.0f;
        ++wave_;
    }

    std::int32_t target = 4;
    switch (mode_) {
        case 1: target = 7 + wave_; break;
        case 2: target = 5 + wave_ * 3 / 2; break;
        case 3: target = 2; break;
        case 4: target = 6 + wave_; break;
        default: target = 4 + wave_ / 2; break;
    }
    target = std::clamp(target, 1, static_cast<std::int32_t>(max_enemies));

    std::int32_t alive = 0;
    for (const Enemy& enemy : enemies_) {
        if (enemy.active) ++alive;
    }

    spawnTimer_ -= dt;
    if (alive < target && spawnTimer_ <= 0.0f) {
        spawnEnemy();
        spawnTimer_ = std::max(0.55f, 2.4f - static_cast<float>(wave_) * 0.09f);
    }

    for (std::size_t i = 0; i < shapes_.size(); ++i) {
        if (!shapes_[i].active) spawnShape(i, false);
    }
}

void TankSimulation::step(float deltaSeconds) noexcept {
    const float dt = std::clamp(deltaSeconds, 0.0f, 0.05f);
    time_ += dt;

    updatePlayer(dt);
    updateEnemies(dt);
    updateShapes(dt);
    updateBullets(dt);
    updateParticles(dt);
    updateSpawning(dt);
}

void TankSimulation::snapshot(float* out, std::size_t capacity) const noexcept {
    if (out == nullptr || capacity < snapshot_floats) return;

    for (std::size_t i = 0; i < snapshot_floats; ++i) out[i] = 0.0f;

    std::int32_t activeBullets = 0;
    std::int32_t activeEnemies = 0;
    std::int32_t activeShapes = 0;
    std::int32_t activeParticles = 0;

    std::size_t cursor = header_floats;
    for (const Bullet& bullet : bullets_) {
        if (!bullet.active) continue;
        out[cursor + 0] = bullet.position.x;
        out[cursor + 1] = bullet.position.y;
        out[cursor + 2] = bullet.radius;
        out[cursor + 3] = static_cast<float>(bullet.owner);
        out[cursor + 4] = bullet.maxLife > 0.0f ? bullet.life / bullet.maxLife : 0.0f;
        out[cursor + 5] = std::atan2(bullet.velocity.y, bullet.velocity.x) * to_degrees;
        cursor += bullet_floats;
        ++activeBullets;
    }

    cursor = header_floats + max_bullets * bullet_floats;
    for (const Enemy& enemy : enemies_) {
        if (!enemy.active) continue;
        out[cursor + 0] = enemy.position.x;
        out[cursor + 1] = enemy.position.y;
        out[cursor + 2] = enemy.heading * to_degrees;
        out[cursor + 3] = enemy.turret * to_degrees;
        out[cursor + 4] = enemy.maxHealth > 0.0f ? enemy.health / enemy.maxHealth : 0.0f;
        out[cursor + 5] = static_cast<float>(enemy.kind);
        out[cursor + 6] = enemy.scale;
        out[cursor + 7] = enemy.flash;
        cursor += enemy_floats;
        ++activeEnemies;
    }

    cursor = header_floats + max_bullets * bullet_floats + max_enemies * enemy_floats;
    for (const Shape& shape : shapes_) {
        if (!shape.active) continue;
        out[cursor + 0] = shape.position.x;
        out[cursor + 1] = shape.position.y;
        out[cursor + 2] = shape.rotation * to_degrees;
        out[cursor + 3] = shape.size;
        out[cursor + 4] = static_cast<float>(shape.sides);
        out[cursor + 5] = shape.maxHealth > 0.0f ? shape.health / shape.maxHealth : 0.0f;
        out[cursor + 6] = shape.flash;
        cursor += shape_floats;
        ++activeShapes;
    }

    cursor = header_floats + max_bullets * bullet_floats + max_enemies * enemy_floats +
             max_shapes * shape_floats;
    for (const Particle& particle : particles_) {
        if (!particle.active) continue;
        out[cursor + 0] = particle.position.x;
        out[cursor + 1] = particle.position.y;
        out[cursor + 2] = particle.maxLife > 0.0f ? particle.life / particle.maxLife : 0.0f;
        out[cursor + 3] = particle.size;
        out[cursor + 4] = static_cast<float>(particle.kind);
        out[cursor + 5] = std::atan2(particle.velocity.y, particle.velocity.x) * to_degrees;
        cursor += particle_floats;
        ++activeParticles;
    }

    out[0] = player_.position.x;
    out[1] = player_.position.y;
    out[2] = player_.heading * to_degrees;
    out[3] = player_.turret * to_degrees;
    out[4] = lengthOf(player_.velocity);
    out[5] = player_.health;
    out[6] = player_.maxHealth;
    out[7] = player_.energy;
    out[8] = player_.maxEnergy;
    out[9] = static_cast<float>(player_.level);
    out[10] = player_.xp;
    out[11] = experienceForLevel(player_.level);
    out[12] = player_.score;
    out[13] = cameraZoom_;
    out[14] = std::clamp(shieldTimer_ / 4.5f, 0.0f, 1.0f);
    out[15] = std::clamp(overdriveTimer_ / 6.0f, 0.0f, 1.0f);
    out[16] = std::clamp(scanTimer_ / 6.5f, 0.0f, 1.0f);
    out[17] = arenaHalfWidth_;
    out[18] = arenaHalfHeight_;
    out[19] = player_.alive ? 1.0f : 0.0f;
    out[20] = static_cast<float>(wave_);
    out[21] = static_cast<float>(player_.kills);
    out[22] = time_;
    out[23] = player_.reload <= 0.0f ? 1.0f : 1.0f - std::clamp(player_.reload * reloadStat(), 0.0f, 1.0f);
    out[24] = static_cast<float>(mode_);
    out[25] = static_cast<float>(player_.statPoints);
    out[26] = static_cast<float>(activeBullets);
    out[27] = static_cast<float>(activeEnemies);
    out[28] = static_cast<float>(activeShapes);
    out[29] = static_cast<float>(activeParticles);
    out[30] = player_.recoil;
    out[31] = player_.muzzleFlash;
    out[32] = player_.damageFlash;
    out[33] = player_.respawnTimer;
    out[34] = threat_;
    out[35] = static_cast<float>(hull_);
    out[36] = static_cast<float>(graphicsQuality_);
    out[37] = static_cast<float>(hullBarrels(hull_));
    out[38] = regenStat();
    out[39] = static_cast<float>(engine_api);
}

}

namespace {

std::mutex gMutex;
omni::tank::TankSimulation gSimulation;

}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_omni_tank_Engine_nativeGetLayout(JNIEnv* env, jobject) {
    const jint values[] = {
        static_cast<jint>(omni::tank::snapshot_floats),
        static_cast<jint>(omni::tank::header_floats),
        static_cast<jint>(omni::tank::max_bullets),
        static_cast<jint>(omni::tank::bullet_floats),
        static_cast<jint>(omni::tank::max_enemies),
        static_cast<jint>(omni::tank::enemy_floats),
        static_cast<jint>(omni::tank::max_shapes),
        static_cast<jint>(omni::tank::shape_floats),
        static_cast<jint>(omni::tank::max_particles),
        static_cast<jint>(omni::tank::particle_floats),
        static_cast<jint>(omni::tank::upgrade_count),
        static_cast<jint>(omni::tank::ability_count)
    };

    constexpr jsize count = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jintArray result = env->NewIntArray(count);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_tank_Engine_nativeStep(JNIEnv* env, jobject, jfloat deltaSeconds, jfloatArray out) {
    if (out == nullptr) return JNI_FALSE;

    const jsize capacity = env->GetArrayLength(out);
    if (capacity < static_cast<jsize>(omni::tank::snapshot_floats)) return JNI_FALSE;

    static thread_local std::array<float, omni::tank::snapshot_floats> buffer{};
    {
        std::scoped_lock lock(gMutex);
        gSimulation.step(deltaSeconds);
        gSimulation.snapshot(buffer.data(), buffer.size());
    }

    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(omni::tank::snapshot_floats), buffer.data());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeReset(JNIEnv*, jobject) {
    std::scoped_lock lock(gMutex);
    gSimulation.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeRespawn(JNIEnv*, jobject) {
    std::scoped_lock lock(gMutex);
    gSimulation.respawn();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omni_tank_Engine_nativeGetEngineInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF("Omni Tank Native Engine | C++26 | API 5 | Simulation AI Physics Particles");
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetMode(JNIEnv*, jobject, jint mode) {
    std::scoped_lock lock(gMutex);
    gSimulation.setMode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetHull(JNIEnv*, jobject, jint hull) {
    std::scoped_lock lock(gMutex);
    gSimulation.setHull(hull);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetGraphicsQuality(JNIEnv*, jobject, jint level) {
    std::scoped_lock lock(gMutex);
    gSimulation.setGraphicsQuality(level);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetDeveloperFlag(JNIEnv*, jobject, jint flag, jboolean enabled) {
    std::scoped_lock lock(gMutex);
    gSimulation.setDeveloperFlag(flag, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetInput(JNIEnv*, jobject, jfloat moveX, jfloat moveY,
                                         jfloat aimX, jfloat aimY, jboolean firing) {
    std::scoped_lock lock(gMutex);
    gSimulation.setInput(moveX, moveY, aimX, aimY, firing == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_tank_Engine_nativeTriggerAbility(JNIEnv*, jobject, jint index) {
    std::scoped_lock lock(gMutex);
    return gSimulation.triggerAbility(index) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_tank_Engine_nativeUpgrade(JNIEnv*, jobject, jint stat) {
    std::scoped_lock lock(gMutex);
    return gSimulation.upgrade(stat) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_omni_tank_Engine_nativeGetUpgrades(JNIEnv* env, jobject) {
    constexpr jsize count = static_cast<jsize>(omni::tank::upgrade_count) + 2;
    jint values[count]{};

    {
        std::scoped_lock lock(gMutex);
        for (jsize i = 0; i < static_cast<jsize>(omni::tank::upgrade_count); ++i) {
            values[i] = gSimulation.upgradeLevel(static_cast<std::int32_t>(i));
        }
        values[count - 2] = gSimulation.statPoints();
        values[count - 1] = gSimulation.level();
    }

    jintArray result = env->NewIntArray(count);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omni_tank_Engine_nativeGetAbilityCooldowns(JNIEnv* env, jobject) {
    constexpr jsize count = static_cast<jsize>(omni::tank::ability_count);
    jfloat values[count]{};

    {
        std::scoped_lock lock(gMutex);
        for (jsize i = 0; i < count; ++i) {
            values[i] = gSimulation.abilityCooldownRatio(static_cast<std::int32_t>(i));
        }
    }

    jfloatArray result = env->NewFloatArray(count);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, count, values);
    return result;
}
