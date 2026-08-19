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
constexpr float to_radians = 0.0174532925f;

constexpr float base_rate = 2.6f;
constexpr float base_damage = 15.0f;
constexpr float base_bullet_speed = 780.0f;
constexpr float base_bullet_life = 1.5f;
constexpr float base_bullet_radius = 8.0f;
constexpr float base_move_speed = 320.0f;
constexpr float base_health = 100.0f;

constexpr float player_radius = 44.0f;
constexpr float bot_radius = 42.0f;
constexpr float match_length = 600.0f;

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
    return 28.0f + static_cast<float>(level) * 30.0f;
}

Barrel gun(float angle, float length, float width, float offset, float damage,
           float speed, float spread, float delay) noexcept {
    Barrel barrel{};
    barrel.angle = angle * to_radians;
    barrel.length = length;
    barrel.width = width;
    barrel.offset = offset;
    barrel.damage = damage;
    barrel.speed = speed;
    barrel.spread = spread;
    barrel.delay = delay;
    barrel.recoilOnly = 0.0f;
    return barrel;
}

Barrel thruster(float angle, float length, float width, float delay) noexcept {
    Barrel barrel = gun(angle, length, width, 0.0f, 0.34f, 0.72f, 0.09f, delay);
    barrel.recoilOnly = 1.0f;
    return barrel;
}

TankDefinition makeTank(std::string_view name, std::int32_t tier, float reload, float damage,
                        float bulletSpeed, float bulletLife, float bulletRadius, float moveSpeed,
                        float maxHealth, float bodyDamage, float vision, float stealth) noexcept {
    TankDefinition definition{};
    definition.name = name;
    definition.tier = tier;
    definition.unlockLevel = tierUnlockLevel(tier);
    definition.reload = reload;
    definition.damage = damage;
    definition.bulletSpeed = bulletSpeed;
    definition.bulletLife = bulletLife;
    definition.bulletRadius = bulletRadius;
    definition.moveSpeed = moveSpeed;
    definition.maxHealth = maxHealth;
    definition.bodyDamage = bodyDamage;
    definition.vision = vision;
    definition.stealth = stealth;
    definition.barrelCount = 0;
    definition.childCount = 0;
    return definition;
}

void addBarrel(TankDefinition& definition, const Barrel& barrel) noexcept {
    if (definition.barrelCount >= static_cast<std::int32_t>(max_barrels)) return;
    definition.barrels[static_cast<std::size_t>(definition.barrelCount)] = barrel;
    ++definition.barrelCount;
}

void addChildren(TankDefinition& definition, std::initializer_list<std::int32_t> ids) noexcept {
    for (std::int32_t id : ids) {
        if (definition.childCount >= static_cast<std::int32_t>(max_children)) return;
        definition.children[static_cast<std::size_t>(definition.childCount)] = id;
        ++definition.childCount;
    }
}

std::array<TankDefinition, tank_count> buildTanks() noexcept {
    std::array<TankDefinition, tank_count> tanks{};

    TankDefinition& basic = tanks[0];
    basic = makeTank("Basic", 1, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(basic, gun(0.0f, 96.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.0f));
    addChildren(basic, {1, 2, 3, 4});

    TankDefinition& twin = tanks[1];
    twin = makeTank("Twin", 2, 1.52f, 0.6f, 1.0f, 1.0f, 0.92f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(twin, gun(0.0f, 92.0f, 24.0f, -14.0f, 1.0f, 1.0f, 0.03f, 0.0f));
    addBarrel(twin, gun(0.0f, 92.0f, 24.0f, 14.0f, 1.0f, 1.0f, 0.03f, 0.5f));
    addChildren(twin, {5, 6, 13});

    TankDefinition& sniper = tanks[2];
    sniper = makeTank("Sniper", 2, 0.62f, 1.62f, 1.55f, 1.55f, 1.0f, 0.94f, 1.0f, 1.0f, 0.28f, 0.0f);
    addBarrel(sniper, gun(0.0f, 128.0f, 27.0f, 0.0f, 1.0f, 1.0f, 0.006f, 0.0f));
    addChildren(sniper, {7, 8});

    TankDefinition& machineGun = tanks[3];
    machineGun = makeTank("Machine Gun", 2, 1.95f, 0.58f, 0.94f, 0.86f, 1.12f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(machineGun, gun(0.0f, 84.0f, 46.0f, 0.0f, 1.0f, 1.0f, 0.17f, 0.0f));
    addChildren(machineGun, {9, 10});

    TankDefinition& flankGuard = tanks[4];
    flankGuard = makeTank("Flank Guard", 2, 1.0f, 0.94f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(flankGuard, gun(0.0f, 96.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.0f));
    addBarrel(flankGuard, gun(180.0f, 80.0f, 26.0f, 0.0f, 0.72f, 0.9f, 0.03f, 0.5f));
    addChildren(flankGuard, {11, 12});

    TankDefinition& tripleShot = tanks[5];
    tripleShot = makeTank("Triple Shot", 3, 1.16f, 0.62f, 1.0f, 1.0f, 0.94f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(tripleShot, gun(-26.0f, 84.0f, 26.0f, 0.0f, 1.0f, 1.0f, 0.03f, 0.0f));
    addBarrel(tripleShot, gun(0.0f, 96.0f, 28.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.0f));
    addBarrel(tripleShot, gun(26.0f, 84.0f, 26.0f, 0.0f, 1.0f, 1.0f, 0.03f, 0.0f));
    addChildren(tripleShot, {14, 15});

    TankDefinition& twinFlank = tanks[6];
    twinFlank = makeTank("Twin Flank", 3, 1.5f, 0.56f, 1.0f, 1.0f, 0.92f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(twinFlank, gun(0.0f, 90.0f, 23.0f, -14.0f, 1.0f, 1.0f, 0.03f, 0.0f));
    addBarrel(twinFlank, gun(0.0f, 90.0f, 23.0f, 14.0f, 1.0f, 1.0f, 0.03f, 0.5f));
    addBarrel(twinFlank, gun(180.0f, 90.0f, 23.0f, -14.0f, 1.0f, 1.0f, 0.03f, 0.25f));
    addBarrel(twinFlank, gun(180.0f, 90.0f, 23.0f, 14.0f, 1.0f, 1.0f, 0.03f, 0.75f));
    addChildren(twinFlank, {13, 21});

    TankDefinition& assassin = tanks[7];
    assassin = makeTank("Assassin", 3, 0.5f, 2.12f, 1.85f, 1.8f, 1.0f, 0.92f, 0.94f, 1.0f, 0.4f, 0.0f);
    addBarrel(assassin, gun(0.0f, 140.0f, 26.0f, 0.0f, 1.0f, 1.0f, 0.004f, 0.0f));
    addChildren(assassin, {16, 17});

    TankDefinition& hunter = tanks[8];
    hunter = makeTank("Hunter", 3, 0.86f, 0.78f, 1.42f, 1.42f, 1.0f, 0.96f, 1.0f, 1.0f, 0.2f, 0.0f);
    addBarrel(hunter, gun(0.0f, 118.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.01f, 0.0f));
    addBarrel(hunter, gun(0.0f, 100.0f, 22.0f, 0.0f, 0.86f, 1.06f, 0.01f, 0.14f));
    addChildren(hunter, {18});

    TankDefinition& gunner = tanks[9];
    gunner = makeTank("Gunner", 3, 2.45f, 0.34f, 1.08f, 0.94f, 0.7f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(gunner, gun(0.0f, 88.0f, 16.0f, -24.0f, 1.0f, 1.0f, 0.03f, 0.0f));
    addBarrel(gunner, gun(0.0f, 88.0f, 16.0f, 24.0f, 1.0f, 1.0f, 0.03f, 0.5f));
    addBarrel(gunner, gun(0.0f, 76.0f, 13.0f, -9.0f, 0.86f, 1.0f, 0.04f, 0.25f));
    addBarrel(gunner, gun(0.0f, 76.0f, 13.0f, 9.0f, 0.86f, 1.0f, 0.04f, 0.75f));
    addChildren(gunner, {18, 23});

    TankDefinition& destroyer = tanks[10];
    destroyer = makeTank("Destroyer", 3, 0.32f, 3.4f, 0.82f, 1.16f, 2.2f, 0.9f, 1.0f, 1.0f, 0.1f, 0.0f);
    addBarrel(destroyer, gun(0.0f, 92.0f, 52.0f, 0.0f, 1.0f, 1.0f, 0.012f, 0.0f));
    addChildren(destroyer, {19});

    TankDefinition& triAngle = tanks[11];
    triAngle = makeTank("Tri-Angle", 3, 1.0f, 0.9f, 1.0f, 1.0f, 1.0f, 1.26f, 0.94f, 1.0f, 0.0f, 0.0f);
    addBarrel(triAngle, gun(0.0f, 96.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.0f));
    addBarrel(triAngle, thruster(150.0f, 72.0f, 24.0f, 0.3f));
    addBarrel(triAngle, thruster(210.0f, 72.0f, 24.0f, 0.6f));
    addChildren(triAngle, {20});

    TankDefinition& quadTank = tanks[12];
    quadTank = makeTank("Quad Tank", 3, 1.1f, 0.76f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(quadTank, gun(0.0f, 92.0f, 28.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.0f));
    addBarrel(quadTank, gun(90.0f, 92.0f, 28.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.25f));
    addBarrel(quadTank, gun(180.0f, 92.0f, 28.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.5f));
    addBarrel(quadTank, gun(270.0f, 92.0f, 28.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.75f));
    addChildren(quadTank, {21});

    TankDefinition& triplet = tanks[13];
    triplet = makeTank("Triplet", 4, 1.82f, 0.52f, 1.02f, 1.0f, 0.94f, 1.0f, 1.06f, 1.0f, 0.0f, 0.0f);
    addBarrel(triplet, gun(0.0f, 100.0f, 26.0f, 0.0f, 1.12f, 1.0f, 0.02f, 0.0f));
    addBarrel(triplet, gun(0.0f, 86.0f, 22.0f, -22.0f, 1.0f, 1.0f, 0.03f, 0.33f));
    addBarrel(triplet, gun(0.0f, 86.0f, 22.0f, 22.0f, 1.0f, 1.0f, 0.03f, 0.66f));
    addChildren(triplet, {23});

    TankDefinition& pentaShot = tanks[14];
    pentaShot = makeTank("Penta Shot", 4, 1.24f, 0.5f, 1.0f, 1.0f, 0.92f, 0.98f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(pentaShot, gun(-44.0f, 74.0f, 22.0f, 0.0f, 1.0f, 0.95f, 0.03f, 0.5f));
    addBarrel(pentaShot, gun(-22.0f, 86.0f, 24.0f, 0.0f, 1.0f, 1.0f, 0.03f, 0.25f));
    addBarrel(pentaShot, gun(0.0f, 100.0f, 26.0f, 0.0f, 1.15f, 1.0f, 0.02f, 0.0f));
    addBarrel(pentaShot, gun(22.0f, 86.0f, 24.0f, 0.0f, 1.0f, 1.0f, 0.03f, 0.25f));
    addBarrel(pentaShot, gun(44.0f, 74.0f, 22.0f, 0.0f, 1.0f, 0.95f, 0.03f, 0.5f));
    addChildren(pentaShot, {22});

    TankDefinition& spreadShot = tanks[15];
    spreadShot = makeTank("Spread Shot", 4, 1.06f, 0.4f, 0.96f, 0.9f, 0.88f, 0.98f, 1.0f, 1.0f, 0.0f, 0.0f);
    addBarrel(spreadShot, gun(-66.0f, 58.0f, 18.0f, 0.0f, 1.0f, 0.86f, 0.03f, 0.6f));
    addBarrel(spreadShot, gun(-48.0f, 66.0f, 18.0f, 0.0f, 1.0f, 0.9f, 0.03f, 0.45f));
    addBarrel(spreadShot, gun(-30.0f, 74.0f, 18.0f, 0.0f, 1.0f, 0.94f, 0.03f, 0.3f));
    addBarrel(spreadShot, gun(0.0f, 104.0f, 28.0f, 0.0f, 1.5f, 1.0f, 0.02f, 0.0f));
    addBarrel(spreadShot, gun(30.0f, 74.0f, 18.0f, 0.0f, 1.0f, 0.94f, 0.03f, 0.3f));
    addBarrel(spreadShot, gun(48.0f, 66.0f, 18.0f, 0.0f, 1.0f, 0.9f, 0.03f, 0.45f));
    addBarrel(spreadShot, gun(66.0f, 58.0f, 18.0f, 0.0f, 1.0f, 0.86f, 0.03f, 0.6f));
    addChildren(spreadShot, {22});

    TankDefinition& ranger = tanks[16];
    ranger = makeTank("Ranger", 4, 0.42f, 2.6f, 2.1f, 2.4f, 1.0f, 0.9f, 0.92f, 1.0f, 0.62f, 0.0f);
    addBarrel(ranger, gun(0.0f, 152.0f, 26.0f, 0.0f, 1.0f, 1.0f, 0.002f, 0.0f));
    addChildren(ranger, {24});

    TankDefinition& stalker = tanks[17];
    stalker = makeTank("Stalker", 4, 0.5f, 2.3f, 1.9f, 1.85f, 1.0f, 1.0f, 0.9f, 1.0f, 0.44f, 1.0f);
    addBarrel(stalker, gun(0.0f, 142.0f, 26.0f, 0.0f, 1.0f, 1.0f, 0.004f, 0.0f));
    addChildren(stalker, {24});

    TankDefinition& streamliner = tanks[18];
    streamliner = makeTank("Streamliner", 4, 2.6f, 0.34f, 1.32f, 1.2f, 0.76f, 0.96f, 1.0f, 1.0f, 0.16f, 0.0f);
    addBarrel(streamliner, gun(0.0f, 122.0f, 22.0f, 0.0f, 1.0f, 1.0f, 0.012f, 0.0f));
    addBarrel(streamliner, gun(0.0f, 112.0f, 20.0f, 0.0f, 0.94f, 1.0f, 0.014f, 0.2f));
    addBarrel(streamliner, gun(0.0f, 102.0f, 18.0f, 0.0f, 0.88f, 1.0f, 0.016f, 0.4f));
    addBarrel(streamliner, gun(0.0f, 92.0f, 16.0f, 0.0f, 0.82f, 1.0f, 0.018f, 0.6f));
    addBarrel(streamliner, gun(0.0f, 82.0f, 14.0f, 0.0f, 0.76f, 1.0f, 0.02f, 0.8f));
    addChildren(streamliner, {23});

    TankDefinition& annihilator = tanks[19];
    annihilator = makeTank("Annihilator", 4, 0.27f, 4.7f, 0.74f, 1.2f, 3.05f, 0.86f, 1.08f, 1.1f, 0.14f, 0.0f);
    addBarrel(annihilator, gun(0.0f, 92.0f, 68.0f, 0.0f, 1.0f, 1.0f, 0.01f, 0.0f));
    addChildren(annihilator, {25});

    TankDefinition& booster = tanks[20];
    booster = makeTank("Booster", 4, 1.0f, 0.86f, 1.0f, 1.0f, 1.0f, 1.46f, 0.9f, 1.0f, 0.0f, 0.0f);
    addBarrel(booster, gun(0.0f, 96.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.02f, 0.0f));
    addBarrel(booster, thruster(150.0f, 70.0f, 22.0f, 0.2f));
    addBarrel(booster, thruster(210.0f, 70.0f, 22.0f, 0.45f));
    addBarrel(booster, thruster(168.0f, 78.0f, 24.0f, 0.7f));
    addBarrel(booster, thruster(192.0f, 78.0f, 24.0f, 0.9f));
    addChildren(booster, {25});

    TankDefinition& octoTank = tanks[21];
    octoTank = makeTank("Octo Tank", 4, 1.02f, 0.6f, 1.0f, 1.0f, 0.96f, 0.98f, 1.08f, 1.0f, 0.0f, 0.0f);
    for (std::int32_t i = 0; i < 8; ++i) {
        const float angle = static_cast<float>(i) * 45.0f;
        addBarrel(octoTank, gun(angle, 90.0f, 26.0f, 0.0f, 1.0f, 1.0f, 0.02f,
                                static_cast<float>(i) * 0.125f));
    }
    addChildren(octoTank, {22});

    TankDefinition& omniStorm = tanks[22];
    omniStorm = makeTank("Omni Storm", 5, 1.42f, 0.52f, 1.06f, 1.05f, 0.94f, 1.0f, 1.22f, 1.15f, 0.12f, 0.0f);
    for (std::int32_t i = 0; i < 12; ++i) {
        const float angle = static_cast<float>(i) * 30.0f;
        addBarrel(omniStorm, gun(angle, 88.0f, 24.0f, 0.0f, 1.0f, 1.0f, 0.025f,
                                 static_cast<float>(i) / 12.0f));
    }

    TankDefinition& vulcan = tanks[23];
    vulcan = makeTank("Vulcan", 5, 3.6f, 0.3f, 1.24f, 0.98f, 0.72f, 1.0f, 1.12f, 1.0f, 0.08f, 0.0f);
    addBarrel(vulcan, gun(0.0f, 104.0f, 18.0f, -26.0f, 1.0f, 1.0f, 0.05f, 0.0f));
    addBarrel(vulcan, gun(0.0f, 104.0f, 18.0f, 26.0f, 1.0f, 1.0f, 0.05f, 0.16f));
    addBarrel(vulcan, gun(0.0f, 94.0f, 16.0f, -13.0f, 0.94f, 1.0f, 0.06f, 0.33f));
    addBarrel(vulcan, gun(0.0f, 94.0f, 16.0f, 13.0f, 0.94f, 1.0f, 0.06f, 0.5f));
    addBarrel(vulcan, gun(0.0f, 116.0f, 20.0f, 0.0f, 1.1f, 1.06f, 0.04f, 0.66f));
    addBarrel(vulcan, thruster(180.0f, 64.0f, 22.0f, 0.83f));

    TankDefinition& phantom = tanks[24];
    phantom = makeTank("Phantom", 5, 0.6f, 2.95f, 2.05f, 2.1f, 1.05f, 1.16f, 0.95f, 1.0f, 0.56f, 1.0f);
    addBarrel(phantom, gun(0.0f, 150.0f, 28.0f, 0.0f, 1.0f, 1.0f, 0.002f, 0.0f));
    addBarrel(phantom, thruster(180.0f, 66.0f, 20.0f, 0.5f));

    TankDefinition& siege = tanks[25];
    siege = makeTank("Siege Breaker", 5, 0.34f, 4.2f, 0.8f, 1.3f, 2.85f, 0.9f, 1.55f, 1.25f, 0.16f, 0.0f);
    addBarrel(siege, gun(0.0f, 96.0f, 66.0f, 0.0f, 1.0f, 1.0f, 0.01f, 0.0f));
    addBarrel(siege, gun(-58.0f, 76.0f, 24.0f, 0.0f, 0.3f, 1.3f, 0.04f, 0.25f));
    addBarrel(siege, gun(58.0f, 76.0f, 24.0f, 0.0f, 0.3f, 1.3f, 0.04f, 0.5f));
    addBarrel(siege, thruster(165.0f, 74.0f, 26.0f, 0.7f));
    addBarrel(siege, thruster(195.0f, 74.0f, 26.0f, 0.9f));

    return tanks;
}

const std::array<TankDefinition, tank_count>& tankTable() noexcept {
    static const std::array<TankDefinition, tank_count> tanks = buildTanks();
    return tanks;
}

}

std::int32_t tierUnlockLevel(std::int32_t tier) noexcept {
    switch (tier) {
        case 2: return 5;
        case 3: return 15;
        case 4: return 30;
        case 5: return 45;
        default: return 1;
    }
}

const TankDefinition& tankDefinition(std::int32_t id) noexcept {
    const auto& tanks = tankTable();
    const std::size_t index = static_cast<std::size_t>(
        std::clamp(id, 0, static_cast<std::int32_t>(tank_count) - 1));
    return tanks[index];
}

TankSimulation::TankSimulation() noexcept {
    startMatch(mode_survival, 0);
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

bool TankSimulation::cheat(std::int32_t index) const noexcept {
    if (index < 0 || index >= static_cast<std::int32_t>(cheat_toggle_count)) return false;
    return cheats_[static_cast<std::size_t>(index)];
}

void TankSimulation::reset() noexcept {
    startMatch(mode_, player_.tankId);
}

void TankSimulation::startMatch(std::int32_t mode, std::int32_t tankId) noexcept {
    mode_ = std::clamp(mode, 0, 1);
    player_ = {};
    input_ = {};
    bullets_ = {};
    bots_ = {};
    shapes_ = {};
    particles_ = {};
    upgrades_ = {};
    teamScore_ = {};

    time_ = 0.0f;
    matchTimer_ = mode_ == mode_team_battle ? match_length : 0.0f;
    spawnTimer_ = 1.2f;
    waveTimer_ = 0.0f;
    cameraZoom_ = 1.0f;
    wave_ = 1;
    winner_ = -1;
    matchOver_ = false;
    rng_ = 0x9E3779B9u;

    player_.tankId = std::clamp(tankId, 0, static_cast<std::int32_t>(tank_count) - 1);
    const TankDefinition& definition = tankDefinition(player_.tankId);
    player_.level = std::max(1, definition.unlockLevel);
    player_.maxHealth = maxHealthStat();
    player_.health = player_.maxHealth;
    player_.alive = true;
    player_.spawnGuard = 2.6f;
    for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
        player_.barrelTimers[static_cast<std::size_t>(i)] =
            definition.barrels[static_cast<std::size_t>(i)].delay / std::max(0.2f, reloadStat());
    }

    spawnWorld();

    if (mode_ == mode_team_battle) {
        for (std::int32_t i = 0; i < 9; ++i) {
            spawnBot(0, 1 + static_cast<std::int32_t>(random() * 12.0f),
                     {-arenaHalfWidth_ * 0.6f + randomRange(-500.0f, 500.0f),
                      randomRange(-arenaHalfHeight_ * 0.7f, arenaHalfHeight_ * 0.7f)});
        }
        for (std::int32_t i = 0; i < 10; ++i) {
            spawnBot(1, 1 + static_cast<std::int32_t>(random() * 12.0f),
                     {arenaHalfWidth_ * 0.6f + randomRange(-500.0f, 500.0f),
                      randomRange(-arenaHalfHeight_ * 0.7f, arenaHalfHeight_ * 0.7f)});
        }
        player_.position = {-arenaHalfWidth_ * 0.6f, 0.0f};
    }
}

void TankSimulation::spawnWorld() noexcept {
    for (std::size_t i = 0; i < shapes_.size(); ++i) spawnShape(i, true);
}

void TankSimulation::spawnShape(std::size_t index, bool anywhere) noexcept {
    Shape& shape = shapes_[index];
    const float roll = random();

    if (roll > 0.95f) {
        shape.sides = 5;
        shape.size = randomRange(58.0f, 74.0f);
        shape.maxHealth = 210.0f;
    } else if (roll > 0.7f) {
        shape.sides = 3;
        shape.size = randomRange(36.0f, 46.0f);
        shape.maxHealth = 48.0f;
    } else {
        shape.sides = 4;
        shape.size = randomRange(26.0f, 36.0f);
        shape.maxHealth = 22.0f;
    }

    shape.health = shape.maxHealth;
    shape.rotation = randomRange(0.0f, two_pi);
    shape.spin = randomRange(-0.5f, 0.5f);
    shape.velocity = {randomRange(-15.0f, 15.0f), randomRange(-15.0f, 15.0f)};
    shape.flash = 0.0f;
    shape.active = true;

    if (anywhere) {
        shape.position = {randomRange(-arenaHalfWidth_, arenaHalfWidth_),
                          randomRange(-arenaHalfHeight_, arenaHalfHeight_)};
    } else {
        const float angle = randomRange(0.0f, two_pi);
        const float radius = randomRange(1400.0f, 2400.0f);
        shape.position = {player_.position.x + std::cos(angle) * radius,
                          player_.position.y + std::sin(angle) * radius};
    }

    clampToArena(shape.position, shape.size);
}

std::int32_t TankSimulation::rosterTank(std::int32_t level) noexcept {
    std::array<std::int32_t, tank_count> pool{};
    std::int32_t count = 0;

    for (std::int32_t id = 0; id < static_cast<std::int32_t>(tank_count); ++id) {
        const TankDefinition& definition = tankDefinition(id);
        if (definition.unlockLevel > level) continue;
        if (level >= 30 && definition.tier <= 2) continue;
        if (level >= 15 && definition.tier == 1) continue;
        pool[static_cast<std::size_t>(count)] = id;
        ++count;
    }

    if (count == 0) return 0;
    const std::int32_t pick = static_cast<std::int32_t>(random() * static_cast<float>(count));
    return pool[static_cast<std::size_t>(std::clamp(pick, 0, count - 1))];
}

void TankSimulation::spawnBot(std::int32_t team, std::int32_t level, Vec2 origin) noexcept {
    for (Bot& bot : bots_) {
        if (bot.active) continue;

        bot.team = team;
        bot.level = std::max(1, level);
        bot.tankId = rosterTank(bot.level);

        const TankDefinition& definition = tankDefinition(bot.tankId);
        bot.maxHealth = base_health * definition.maxHealth *
                        (1.0f + static_cast<float>(bot.level) * 0.042f);
        bot.health = bot.maxHealth;
        bot.position = origin;
        clampToArena(bot.position, bot_radius);
        bot.velocity = {};
        bot.heading = randomRange(0.0f, two_pi);
        bot.turret = bot.heading;
        bot.scale = 0.94f + static_cast<float>(definition.tier) * 0.04f;
        bot.flash = 0.0f;
        bot.recoil = 0.0f;
        bot.think = randomRange(0.0f, 0.4f);
        bot.wander = randomRange(0.0f, two_pi);
        bot.aggression = randomRange(0.35f, 0.95f);
        bot.accuracy = std::min(0.9f, 0.34f + static_cast<float>(bot.level) * 0.014f +
                                          randomRange(0.0f, 0.2f));
        bot.retreat = 0.0f;
        bot.respawn = 0.0f;
        bot.target = -2;
        bot.barrelTimers = {};
        for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
            bot.barrelTimers[static_cast<std::size_t>(i)] =
                definition.barrels[static_cast<std::size_t>(i)].delay;
        }
        bot.active = true;
        burst(bot.position, 2, 10, 180.0f);
        return;
    }
}

void TankSimulation::burst(Vec2 origin, std::int32_t kind, std::int32_t count, float speed) noexcept {
    if (graphicsQuality_ <= 0) return;
    const std::int32_t scaled = std::max(1, count * std::min(graphicsQuality_ + 1, 4) / 4);

    std::int32_t spawned = 0;
    for (Particle& particle : particles_) {
        if (spawned >= scaled) return;
        if (particle.active) continue;

        const float angle = randomRange(0.0f, two_pi);
        const float magnitude = randomRange(speed * 0.3f, speed);
        particle.position = origin;
        particle.velocity = {std::cos(angle) * magnitude, std::sin(angle) * magnitude};
        particle.maxLife = randomRange(0.3f, 0.8f);
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
    const TankDefinition& definition = tankDefinition(player_.tankId);
    return base_damage * definition.damage *
           (1.0f + static_cast<float>(upgrades_[0]) * 0.16f);
}

float TankSimulation::reloadStat() const noexcept {
    const TankDefinition& definition = tankDefinition(player_.tankId);
    float rate = base_rate * definition.reload * (1.0f + static_cast<float>(upgrades_[1]) * 0.14f);
    if (cheat(cheat_rapid_fire)) rate *= 3.2f;
    if (cheat(cheat_no_cooldown)) rate *= 7.0f;
    return rate;
}

float TankSimulation::bulletSpeedStat() const noexcept {
    const TankDefinition& definition = tankDefinition(player_.tankId);
    return base_bullet_speed * definition.bulletSpeed *
           (1.0f + static_cast<float>(upgrades_[2]) * 0.085f);
}

float TankSimulation::moveSpeedStat() const noexcept {
    const TankDefinition& definition = tankDefinition(player_.tankId);
    float speed = base_move_speed * definition.moveSpeed *
                  (1.0f + static_cast<float>(upgrades_[3]) * 0.08f);
    if (cheat(cheat_speed)) speed *= 2.3f;
    return speed;
}

float TankSimulation::maxHealthStat() const noexcept {
    const TankDefinition& definition = tankDefinition(player_.tankId);
    return base_health * definition.maxHealth *
           (1.0f + static_cast<float>(upgrades_[4]) * 0.18f) *
           (1.0f + static_cast<float>(player_.level) * 0.02f);
}

float TankSimulation::regenStat() const noexcept {
    return 2.2f + static_cast<float>(upgrades_[5]) * 1.9f +
           static_cast<float>(player_.level) * 0.08f;
}

void TankSimulation::setInput(float moveX, float moveY, float aimX, float aimY, bool firing) noexcept {
    input_.moveX = std::clamp(moveX, -1.0f, 1.0f);
    input_.moveY = std::clamp(moveY, -1.0f, 1.0f);
    input_.aimX = std::clamp(aimX, -1.0f, 1.0f);
    input_.aimY = std::clamp(aimY, -1.0f, 1.0f);
    input_.firing = firing;
}

void TankSimulation::setGraphicsQuality(std::int32_t level) noexcept {
    graphicsQuality_ = std::clamp(level, 0, 4);
}

void TankSimulation::setCheat(std::int32_t index, bool enabled) noexcept {
    if (index < 0 || index >= static_cast<std::int32_t>(cheat_toggle_count)) return;
    cheats_[static_cast<std::size_t>(index)] = enabled;
}

void TankSimulation::cheatAction(std::int32_t index) noexcept {
    switch (index) {
        case 0:
            awardExperience(experienceForLevel(player_.level) + 1.0f, 0.0f);
            break;
        case 1:
            for (std::int32_t i = 0; i < 10; ++i) {
                awardExperience(experienceForLevel(player_.level) + 1.0f, 0.0f);
            }
            break;
        case 2:
            for (std::int32_t i = 0; i < static_cast<std::int32_t>(upgrade_count); ++i) {
                upgrades_[static_cast<std::size_t>(i)] = static_cast<std::int32_t>(upgrade_cap);
            }
            player_.maxHealth = maxHealthStat();
            player_.health = player_.maxHealth;
            break;
        case 3:
            for (Bot& bot : bots_) {
                if (!bot.active || bot.team == 0) continue;
                burst(bot.position, 0, 22, 380.0f);
                bot.active = false;
                bot.respawn = mode_ == mode_team_battle ? 5.0f : 0.0f;
                ++player_.kills;
                addTeamScore(0, 100.0f);
            }
            break;
        case 4:
            player_.maxHealth = maxHealthStat();
            player_.health = player_.maxHealth;
            player_.alive = true;
            player_.respawnTimer = 0.0f;
            break;
        case 5:
            player_.position = {};
            player_.velocity = {};
            break;
        case 6:
            player_.statPoints += 10;
            break;
        case 7: {
            std::int32_t best = player_.tankId;
            for (std::int32_t id = 0; id < static_cast<std::int32_t>(tank_count); ++id) {
                if (tankDefinition(id).tier == 5) {
                    best = id;
                    break;
                }
            }
            evolve(best);
            break;
        }
        default:
            break;
    }
}

bool TankSimulation::upgrade(std::int32_t stat) noexcept {
    if (stat < 0 || stat >= static_cast<std::int32_t>(upgrade_count)) return false;
    const std::size_t slot = static_cast<std::size_t>(stat);
    if (player_.statPoints <= 0) return false;
    if (upgrades_[slot] >= static_cast<std::int32_t>(upgrade_cap)) return false;

    ++upgrades_[slot];
    --player_.statPoints;

    const float ratio = player_.maxHealth > 0.0f ? player_.health / player_.maxHealth : 1.0f;
    player_.maxHealth = maxHealthStat();
    player_.health = std::min(player_.maxHealth, player_.maxHealth * ratio + 14.0f);
    return true;
}

bool TankSimulation::evolve(std::int32_t tankId) noexcept {
    if (tankId < 0 || tankId >= static_cast<std::int32_t>(tank_count)) return false;

    const TankDefinition& target = tankDefinition(tankId);
    if (target.unlockLevel > player_.level) return false;

    const TankDefinition& current = tankDefinition(player_.tankId);
    bool allowed = cheats_[static_cast<std::size_t>(cheat_god)] && target.tier == 5;
    for (std::int32_t i = 0; i < current.childCount && !allowed; ++i) {
        if (current.children[static_cast<std::size_t>(i)] == tankId) allowed = true;
    }
    if (!allowed) return false;

    player_.tankId = tankId;
    player_.barrelTimers = {};
    for (std::int32_t i = 0; i < target.barrelCount; ++i) {
        player_.barrelTimers[static_cast<std::size_t>(i)] =
            target.barrels[static_cast<std::size_t>(i)].delay / std::max(0.2f, reloadStat());
    }

    const float ratio = player_.maxHealth > 0.0f ? player_.health / player_.maxHealth : 1.0f;
    player_.maxHealth = maxHealthStat();
    player_.health = std::min(player_.maxHealth, player_.maxHealth * std::max(ratio, 0.55f));
    burst(player_.position, 4, 46, 460.0f);
    return true;
}

void TankSimulation::respawn() noexcept {
    player_.position = mode_ == mode_team_battle
        ? Vec2{-arenaHalfWidth_ * 0.68f, randomRange(-600.0f, 600.0f)}
        : Vec2{};
    player_.velocity = {};
    player_.maxHealth = maxHealthStat();
    player_.health = player_.maxHealth;
    player_.respawnTimer = 0.0f;
    player_.damageFlash = 0.0f;
    player_.spawnGuard = 2.6f;
    player_.alive = true;

    for (Bullet& bullet : bullets_) {
        if (bullet.team != 0) bullet.active = false;
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

void TankSimulation::addTeamScore(std::int32_t team, float points) noexcept {
    if (team < 0 || team > 1) return;
    teamScore_[static_cast<std::size_t>(team)] += points;
}

void TankSimulation::awardExperience(float amount, float points) noexcept {
    const float multiplier = cheat(cheat_xp_boost) ? 10.0f : 1.0f;
    player_.xp += amount * multiplier;
    player_.score += points;

    while (player_.xp >= experienceForLevel(player_.level)) {
        player_.xp -= experienceForLevel(player_.level);
        ++player_.level;
        ++player_.statPoints;
        player_.maxHealth = maxHealthStat();
        player_.health = std::min(player_.maxHealth, player_.health + player_.maxHealth * 0.2f);
        burst(player_.position, 4, 30, 320.0f);
    }
}

void TankSimulation::damagePlayer(float amount) noexcept {
    if (!player_.alive || matchOver_) return;
    if (cheat(cheat_god) || player_.spawnGuard > 0.0f) return;

    player_.health -= amount;
    player_.damageFlash = std::min(1.0f, player_.damageFlash + amount / 45.0f);

    if (player_.health <= 0.0f) {
        player_.health = 0.0f;
        player_.alive = false;
        player_.respawnTimer = mode_ == mode_team_battle ? 5.0f : 3.0f;
        burst(player_.position, 0, 60, 520.0f);
        addTeamScore(1, mode_ == mode_team_battle ? 100.0f : 0.0f);
    }
}

void TankSimulation::fireWeapon(Vec2 origin, float baseAngle, const TankDefinition& definition,
                                float damageScale, float speedScale, float radiusScale,
                                float lifeScale, std::int32_t team, std::int32_t sourceBot,
                                bool fromPlayer, std::array<float, max_barrels>& timers,
                                float& recoilOut, Vec2& velocityOut, float dt) noexcept {
    const float rate = fromPlayer ? reloadStat() : base_rate * definition.reload;
    const float cycle = 1.0f / std::max(0.15f, rate);

    for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
        const std::size_t slot = static_cast<std::size_t>(i);
        const Barrel& barrel = definition.barrels[slot];
        timers[slot] -= dt;
        if (timers[slot] > 0.0f) continue;
        timers[slot] = cycle;

        const float spread = barrel.spread * randomRange(-1.0f, 1.0f);
        const float angle = baseAngle + barrel.angle + spread;
        const float lateral = angle + pi * 0.5f;
        const Vec2 muzzle{
            origin.x + std::cos(angle) * barrel.length + std::cos(lateral) * barrel.offset,
            origin.y + std::sin(angle) * barrel.length + std::sin(lateral) * barrel.offset
        };

        float damage = damageScale * barrel.damage;
        if (fromPlayer && cheat(cheat_one_shot)) damage = 999999.0f;

        float radius = base_bullet_radius * definition.bulletRadius * radiusScale *
                       (0.62f + barrel.width / 60.0f);
        if (fromPlayer && cheat(cheat_giant_bullets)) radius *= 3.0f;

        float life = base_bullet_life * definition.bulletLife * lifeScale;
        if (fromPlayer && cheat(cheat_infinite_range)) life *= 4.0f;

        const float speed = base_bullet_speed * definition.bulletSpeed * speedScale * barrel.speed;

        for (Bullet& bullet : bullets_) {
            if (bullet.active) continue;
            bullet.position = muzzle;
            bullet.velocity = {std::cos(angle) * speed, std::sin(angle) * speed};
            bullet.radius = radius;
            bullet.life = life;
            bullet.maxLife = life;
            bullet.damage = damage;
            bullet.team = team;
            bullet.sourceBot = sourceBot;
            bullet.fromPlayer = fromPlayer;
            bullet.homing = fromPlayer && cheat(cheat_magic_bullet);
            bullet.penetrating = fromPlayer && cheat(cheat_penetration);
            bullet.active = true;
            break;
        }

        const bool noRecoil = fromPlayer && cheat(cheat_no_recoil);
        if (!noRecoil) {
            const float force = barrel.recoilOnly > 0.5f ? 118.0f : 26.0f;
            velocityOut.x -= std::cos(angle) * force * barrel.width / 30.0f;
            velocityOut.y -= std::sin(angle) * force * barrel.width / 30.0f;
        }
        recoilOut = 1.0f;
        burst(muzzle, fromPlayer ? 3 : 7, 3, 120.0f);
    }
}

void TankSimulation::updatePlayer(float dt) noexcept {
    const TankDefinition& definition = tankDefinition(player_.tankId);

    player_.recoil = std::max(0.0f, player_.recoil - dt * 6.0f);
    player_.muzzleFlash = std::max(0.0f, player_.muzzleFlash - dt * 7.0f);
    player_.damageFlash = std::max(0.0f, player_.damageFlash - dt * 1.9f);
    player_.spawnGuard = std::max(0.0f, player_.spawnGuard - dt);

    if (!player_.alive) {
        player_.respawnTimer = std::max(0.0f, player_.respawnTimer - dt);
        player_.velocity = {player_.velocity.x * 0.9f, player_.velocity.y * 0.9f};
        if (mode_ == mode_team_battle && player_.respawnTimer <= 0.0f && !matchOver_) respawn();
        return;
    }

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
        player_.idle = 0.0f;
    } else {
        player_.velocity.x -= player_.velocity.x * std::min(1.0f, dt * 5.0f);
        player_.velocity.y -= player_.velocity.y * std::min(1.0f, dt * 5.0f);
        player_.idle += dt;
    }

    player_.position.x += player_.velocity.x * dt;
    player_.position.y += player_.velocity.y * dt;
    clampToArena(player_.position, player_radius);

    const float aimMagnitude = std::sqrt(input_.aimX * input_.aimX + input_.aimY * input_.aimY);
    bool firing = input_.firing || aimMagnitude > 0.55f || cheat(cheat_auto_fire);

    if (cheat(cheat_aimbot)) {
        const std::int32_t target = nearestEnemyToPlayer(4200.0f);
        if (target >= 0) {
            const Bot& bot = bots_[static_cast<std::size_t>(target)];
            const float bulletSpeed = bulletSpeedStat() * definition.bulletSpeed;
            const float travel = distanceBetween(player_.position, bot.position) /
                                 std::max(120.0f, bulletSpeed);
            const Vec2 lead{bot.position.x + bot.velocity.x * travel,
                            bot.position.y + bot.velocity.y * travel};
            player_.turret = std::atan2(lead.y - player_.position.y, lead.x - player_.position.x);
            firing = true;
        }
    } else if (aimMagnitude > 0.14f) {
        player_.turret = approachAngle(player_.turret, std::atan2(input_.aimY, input_.aimX), dt * 18.0f);
    } else if (magnitude > 0.06f) {
        player_.turret = approachAngle(player_.turret, player_.heading, dt * 4.0f);
    }

    if (definition.stealth > 0.5f) {
        const float target = (player_.idle > 0.9f && !firing) ? 0.12f : 1.0f;
        player_.stealth += (target - player_.stealth) * std::min(1.0f, dt * 2.4f);
    } else {
        player_.stealth += (1.0f - player_.stealth) * std::min(1.0f, dt * 4.0f);
    }

    if (firing && !matchOver_) {
        player_.muzzleFlash = 1.0f;
        fireWeapon(player_.position, player_.turret, definition,
                   damageStat(), bulletSpeedStat() / base_bullet_speed, 1.0f, 1.0f,
                   0, -1, true, player_.barrelTimers, player_.recoil, player_.velocity, dt);
    } else {
        const float cycle = 1.0f / std::max(0.15f, reloadStat());
        for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
            const std::size_t slot = static_cast<std::size_t>(i);
            player_.barrelTimers[slot] = std::max(-cycle, player_.barrelTimers[slot] - dt);
        }
    }

    const float targetZoom = std::min(1.75f, 1.0f + static_cast<float>(player_.level - 1) * 0.009f +
                                                 definition.vision);
    cameraZoom_ += (targetZoom - cameraZoom_) * std::min(1.0f, dt * 1.8f);
}

std::int32_t TankSimulation::nearestEnemyToPlayer(float maxDistance) const noexcept {
    std::int32_t best = -1;
    float bestDistance = maxDistance;

    for (std::size_t i = 0; i < bots_.size(); ++i) {
        const Bot& bot = bots_[i];
        if (!bot.active || bot.team == 0) continue;
        const float distance = distanceBetween(player_.position, bot.position);
        if (distance < bestDistance) {
            bestDistance = distance;
            best = static_cast<std::int32_t>(i);
        }
    }
    return best;
}

Vec2 TankSimulation::targetPosition(std::int32_t target) const noexcept {
    if (target == -1) return player_.position;
    if (target < 0 || target >= static_cast<std::int32_t>(max_bots)) return {};
    return bots_[static_cast<std::size_t>(target)].position;
}

Vec2 TankSimulation::targetVelocity(std::int32_t target) const noexcept {
    if (target == -1) return player_.velocity;
    if (target < 0 || target >= static_cast<std::int32_t>(max_bots)) return {};
    return bots_[static_cast<std::size_t>(target)].velocity;
}

bool TankSimulation::targetAlive(std::int32_t target) const noexcept {
    if (target == -1) return player_.alive;
    if (target < 0 || target >= static_cast<std::int32_t>(max_bots)) return false;
    return bots_[static_cast<std::size_t>(target)].active;
}

std::int32_t TankSimulation::pickTarget(const Bot& bot, std::size_t self) const noexcept {
    std::int32_t best = -2;
    float bestScore = 1e9f;

    if (bot.team != 0 && player_.alive) {
        const float visibility = std::max(0.2f, player_.stealth);
        const float distance = distanceBetween(bot.position, player_.position) / visibility;
        const float bias = mode_ == mode_survival ? 0.25f : 0.8f;
        bestScore = distance * bias;
        best = -1;
    }

    if (mode_ == mode_team_battle) {
        for (std::size_t i = 0; i < bots_.size(); ++i) {
            if (i == self) continue;
            const Bot& other = bots_[i];
            if (!other.active || other.team == bot.team) continue;
            const float distance = distanceBetween(bot.position, other.position);
            const float score = distance * (other.health < other.maxHealth * 0.4f ? 0.72f : 1.0f);
            if (score < bestScore) {
                bestScore = score;
                best = static_cast<std::int32_t>(i);
            }
        }
    }

    if (best != -2 && bestScore > 3400.0f && mode_ == mode_team_battle) return -2;
    return best;
}

void TankSimulation::updateBots(float dt) noexcept {
    const bool frozen = cheat(cheat_freeze);
    const float slow = cheat(cheat_slow_enemies) ? 0.35f : 1.0f;

    for (std::size_t index = 0; index < bots_.size(); ++index) {
        Bot& bot = bots_[index];

        if (!bot.active) {
            if (bot.respawn > 0.0f) {
                bot.respawn -= dt;
                if (bot.respawn <= 0.0f && mode_ == mode_team_battle && !matchOver_) {
                    const float side = bot.team == 0 ? -1.0f : 1.0f;
                    spawnBot(bot.team, 1 + static_cast<std::int32_t>(time_ / 22.0f),
                             {side * arenaHalfWidth_ * 0.72f,
                              randomRange(-arenaHalfHeight_ * 0.8f, arenaHalfHeight_ * 0.8f)});
                }
            }
            continue;
        }

        const TankDefinition& definition = tankDefinition(bot.tankId);
        bot.flash = std::max(0.0f, bot.flash - dt * 4.0f);
        bot.recoil = std::max(0.0f, bot.recoil - dt * 6.0f);
        bot.health = std::min(bot.maxHealth, bot.health + dt * (0.5f + static_cast<float>(bot.level) * 0.02f));

        if (frozen || matchOver_) {
            bot.velocity = {bot.velocity.x * 0.86f, bot.velocity.y * 0.86f};
            bot.position.x += bot.velocity.x * dt;
            bot.position.y += bot.velocity.y * dt;
            continue;
        }

        bot.think -= dt;
        if (bot.think <= 0.0f) {
            bot.think = randomRange(0.25f, 0.7f);
            bot.target = pickTarget(bot, index);
        }
        if (!targetAlive(bot.target)) bot.target = pickTarget(bot, index);

        const float healthRatio = bot.maxHealth > 0.0f ? bot.health / bot.maxHealth : 0.0f;
        if (healthRatio < 0.26f) bot.retreat = 1.0f;
        if (healthRatio > 0.62f) bot.retreat = 0.0f;

        bot.wander += dt * randomRange(0.4f, 1.2f);

        float desiredAngle = bot.wander;
        float speedScale = 0.55f;
        bool engaging = false;
        float aimAngle = bot.turret;
        float distance = 0.0f;

        if (bot.target != -2) {
            const Vec2 position = targetPosition(bot.target);
            const Vec2 velocity = targetVelocity(bot.target);
            distance = distanceBetween(bot.position, position);

            const float bulletSpeed = base_bullet_speed * definition.bulletSpeed;
            const float travel = distance / std::max(140.0f, bulletSpeed);
            const Vec2 lead{position.x + velocity.x * travel * bot.accuracy,
                            position.y + velocity.y * travel * bot.accuracy};

            aimAngle = std::atan2(lead.y - bot.position.y, lead.x - bot.position.x);
            aimAngle += (1.0f - bot.accuracy) * randomRange(-0.22f, 0.22f);

            const float preferred = 380.0f + definition.bulletSpeed * 360.0f +
                                    definition.vision * 700.0f;
            const float toTarget = std::atan2(position.y - bot.position.y, position.x - bot.position.x);

            if (bot.retreat > 0.5f) {
                desiredAngle = toTarget + pi;
                speedScale = 1.0f;
            } else if (distance > preferred * 1.2f) {
                desiredAngle = toTarget;
                speedScale = 1.0f;
            } else if (distance < preferred * 0.68f) {
                desiredAngle = toTarget + pi;
                speedScale = 0.85f;
            } else {
                desiredAngle = toTarget + pi * 0.5f * (std::sin(bot.wander * 0.8f) > 0.0f ? 1.0f : -1.0f);
                speedScale = 0.75f;
            }

            engaging = distance < preferred * 1.9f && bot.retreat < 0.5f;
        } else {
            float bestDistance = 2400.0f;
            Vec2 farm{};
            bool found = false;
            for (const Shape& shape : shapes_) {
                if (!shape.active) continue;
                const float shapeDistance = distanceBetween(bot.position, shape.position);
                if (shapeDistance < bestDistance) {
                    bestDistance = shapeDistance;
                    farm = shape.position;
                    found = true;
                }
            }
            if (found) {
                desiredAngle = std::atan2(farm.y - bot.position.y, farm.x - bot.position.x);
                aimAngle = desiredAngle;
                speedScale = 0.8f;
                engaging = bestDistance < 900.0f;
            }
        }

        const float moveSpeed = base_move_speed * definition.moveSpeed * slow *
                                (0.82f + bot.aggression * 0.3f) * speedScale;

        bot.heading = approachAngle(bot.heading, desiredAngle, dt * 3.6f);
        bot.turret = approachAngle(bot.turret, aimAngle, dt * (4.0f + bot.accuracy * 6.0f));

        bot.velocity.x += (std::cos(bot.heading) * moveSpeed - bot.velocity.x) * std::min(1.0f, dt * 4.0f);
        bot.velocity.y += (std::sin(bot.heading) * moveSpeed - bot.velocity.y) * std::min(1.0f, dt * 4.0f);
        bot.position.x += bot.velocity.x * dt;
        bot.position.y += bot.velocity.y * dt;
        clampToArena(bot.position, bot_radius * bot.scale);

        for (std::size_t other = index + 1; other < bots_.size(); ++other) {
            Bot& neighbour = bots_[other];
            if (!neighbour.active) continue;
            const float contact = (bot_radius * bot.scale + bot_radius * neighbour.scale) * 0.92f;
            const float gap = distanceBetween(bot.position, neighbour.position);
            if (gap >= contact || gap <= 0.001f) continue;
            const float push = (contact - gap) * 0.5f;
            const float angle = std::atan2(neighbour.position.y - bot.position.y,
                                           neighbour.position.x - bot.position.x);
            bot.position.x -= std::cos(angle) * push;
            bot.position.y -= std::sin(angle) * push;
            neighbour.position.x += std::cos(angle) * push;
            neighbour.position.y += std::sin(angle) * push;
        }

        const float aimError = std::abs(wrapAngle(aimAngle - bot.turret));
        if (engaging && aimError < 0.28f) {
            fireWeapon(bot.position, bot.turret, definition,
                       base_damage * definition.damage * (0.52f + static_cast<float>(bot.level) * 0.017f),
                       1.0f, 1.0f, 1.0f, bot.team, static_cast<std::int32_t>(index), false,
                       bot.barrelTimers, bot.recoil, bot.velocity, dt);
        } else {
            for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
                const std::size_t slot = static_cast<std::size_t>(i);
                bot.barrelTimers[slot] = std::max(-1.0f, bot.barrelTimers[slot] - dt);
            }
        }

        if (bot.team != 0 && player_.alive && !cheat(cheat_ghost)) {
            const float contact = player_radius + bot_radius * bot.scale;
            const float gap = distanceBetween(bot.position, player_.position);
            if (gap < contact) {
                damagePlayer(19.0f * definition.bodyDamage * dt);
                const float angle = std::atan2(bot.position.y - player_.position.y,
                                               bot.position.x - player_.position.x);
                bot.position.x += std::cos(angle) * (contact - gap);
                bot.position.y += std::sin(angle) * (contact - gap);
                bot.health -= 20.0f * dt;
                bot.flash = 1.0f;
                if (bot.health <= 0.0f) {
                    bot.active = false;
                    bot.respawn = mode_ == mode_team_battle ? 5.0f : 0.0f;
                    ++player_.kills;
                    burst(bot.position, 0, 26, 400.0f);
                    awardExperience(60.0f + static_cast<float>(bot.level) * 12.0f,
                                    100.0f + static_cast<float>(bot.level) * 15.0f);
                    addTeamScore(0, 100.0f);
                }
            }
        }
    }
}

void TankSimulation::updateShapes(float dt) noexcept {
    for (std::size_t i = 0; i < shapes_.size(); ++i) {
        Shape& shape = shapes_[i];
        if (!shape.active) {
            spawnShape(i, false);
            continue;
        }

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

        if (!player_.alive || cheat(cheat_ghost)) continue;

        const float contact = player_radius + shape.size;
        const float gap = distanceBetween(shape.position, player_.position);
        if (gap >= contact) continue;

        damagePlayer(8.0f * dt);
        shape.health -= 30.0f * dt;
        shape.flash = 1.0f;
        const float angle = std::atan2(shape.position.y - player_.position.y,
                                       shape.position.x - player_.position.x);
        shape.position.x += std::cos(angle) * (contact - gap);
        shape.position.y += std::sin(angle) * (contact - gap);

        if (shape.health <= 0.0f) {
            shape.active = false;
            burst(shape.position, 6, 14, 240.0f);
            awardExperience(shape.maxHealth * 0.8f, shape.maxHealth * 0.4f);
            addTeamScore(0, 10.0f);
        }
    }
}

void TankSimulation::resolveHit(Bullet& bullet, float damage) noexcept {
    if (bullet.fromPlayer && cheat(cheat_vampire)) {
        player_.health = std::min(player_.maxHealth, player_.health + damage * 0.35f);
    }
    if (!bullet.penetrating) bullet.active = false;
}

void TankSimulation::updateBullets(float dt) noexcept {
    for (Bullet& bullet : bullets_) {
        if (!bullet.active) continue;

        bullet.life -= dt;
        if (bullet.life <= 0.0f) {
            bullet.active = false;
            continue;
        }

        if (bullet.homing) {
            const std::int32_t target = nearestEnemyToPlayer(3000.0f);
            if (target >= 0) {
                const Bot& bot = bots_[static_cast<std::size_t>(target)];
                const float desired = std::atan2(bot.position.y - bullet.position.y,
                                                 bot.position.x - bullet.position.x);
                const float speed = lengthOf(bullet.velocity);
                const float current = std::atan2(bullet.velocity.y, bullet.velocity.x);
                const float steered = approachAngle(current, desired, dt * 7.0f);
                bullet.velocity = {std::cos(steered) * speed, std::sin(steered) * speed};
            }
        }

        bullet.position.x += bullet.velocity.x * dt;
        bullet.position.y += bullet.velocity.y * dt;

        if (bullet.position.x < -arenaHalfWidth_ || bullet.position.x > arenaHalfWidth_ ||
            bullet.position.y < -arenaHalfHeight_ || bullet.position.y > arenaHalfHeight_) {
            bullet.active = false;
            burst(bullet.position, bullet.fromPlayer ? 3 : 7, 3, 110.0f);
            continue;
        }

        bool consumed = false;

        for (std::size_t i = 0; i < bots_.size() && !consumed; ++i) {
            Bot& bot = bots_[i];
            if (!bot.active || bot.team == bullet.team) continue;
            if (distanceBetween(bullet.position, bot.position) > bullet.radius + bot_radius * bot.scale) {
                continue;
            }

            bot.health -= bullet.damage;
            bot.flash = 1.0f;
            burst(bullet.position, bullet.fromPlayer ? 3 : 7, 5, 170.0f);
            resolveHit(bullet, bullet.damage);
            consumed = !bullet.penetrating;

            if (bot.health <= 0.0f) {
                bot.active = false;
                bot.respawn = mode_ == mode_team_battle ? 5.0f : 0.0f;
                burst(bot.position, 0, 26, 400.0f);
                addTeamScore(bullet.team, 100.0f);
                if (bullet.fromPlayer) {
                    ++player_.kills;
                    awardExperience(80.0f + static_cast<float>(bot.level) * 14.0f,
                                    110.0f + static_cast<float>(bot.level) * 16.0f);
                }
            }
        }

        if (consumed) continue;

        if (bullet.team != 0 && player_.alive &&
            distanceBetween(bullet.position, player_.position) < bullet.radius + player_radius) {
            damagePlayer(bullet.damage);
            burst(bullet.position, 0, 8, 220.0f);
            if (!bullet.penetrating) bullet.active = false;
            continue;
        }

        for (std::size_t i = 0; i < shapes_.size() && !consumed; ++i) {
            Shape& shape = shapes_[i];
            if (!shape.active) continue;
            if (distanceBetween(bullet.position, shape.position) > bullet.radius + shape.size) continue;

            shape.health -= bullet.damage;
            shape.flash = 1.0f;
            burst(bullet.position, 6, 5, 160.0f);
            resolveHit(bullet, bullet.damage);
            consumed = !bullet.penetrating;

            if (shape.health <= 0.0f) {
                shape.active = false;
                burst(shape.position, 6, 16, 260.0f);
                addTeamScore(bullet.team, 10.0f);
                if (bullet.fromPlayer) awardExperience(shape.maxHealth * 0.8f, shape.maxHealth * 0.4f);
            }
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

void TankSimulation::updateDirector(float dt) noexcept {
    if (mode_ == mode_team_battle) {
        if (!matchOver_) {
            matchTimer_ = std::max(0.0f, matchTimer_ - dt);
            if (matchTimer_ <= 0.0f) {
                matchOver_ = true;
                winner_ = teamScore_[0] >= teamScore_[1] ? 0 : 1;
            }
        }
        return;
    }

    waveTimer_ += dt;
    if (waveTimer_ >= 38.0f) {
        waveTimer_ = 0.0f;
        ++wave_;
    }

    std::int32_t alive = 0;
    for (const Bot& bot : bots_) {
        if (bot.active) ++alive;
    }

    const std::int32_t target = std::clamp(3 + wave_ / 3, 1, 8);
    spawnTimer_ -= dt;
    if (alive < target && spawnTimer_ <= 0.0f) {
        const float angle = randomRange(0.0f, two_pi);
        const float radius = randomRange(1700.0f, 2300.0f);
        const std::int32_t level = std::clamp(
            player_.level + wave_ / 2 - 2 + static_cast<std::int32_t>(random() * 4.0f), 1, 60);
        spawnBot(1, level,
                 {player_.position.x + std::cos(angle) * radius,
                  player_.position.y + std::sin(angle) * radius});
        spawnTimer_ = std::max(0.7f, 2.6f - static_cast<float>(wave_) * 0.07f);
    }
}

void TankSimulation::step(float deltaSeconds) noexcept {
    const float dt = std::clamp(deltaSeconds, 0.0f, 0.05f);
    time_ += dt;

    updatePlayer(dt);
    updateBots(dt);
    updateShapes(dt);
    updateBullets(dt);
    updateParticles(dt);
    updateDirector(dt);
}

void TankSimulation::snapshot(float* out, std::size_t capacity) const noexcept {
    if (out == nullptr || capacity < snapshot_floats) return;
    for (std::size_t i = 0; i < snapshot_floats; ++i) out[i] = 0.0f;

    std::int32_t activeBullets = 0;
    std::int32_t activeBots = 0;
    std::int32_t activeShapes = 0;
    std::int32_t activeParticles = 0;

    std::size_t cursor = header_floats;
    for (const Bullet& bullet : bullets_) {
        if (!bullet.active) continue;
        out[cursor + 0] = bullet.position.x;
        out[cursor + 1] = bullet.position.y;
        out[cursor + 2] = bullet.radius;
        out[cursor + 3] = static_cast<float>(bullet.team);
        out[cursor + 4] = bullet.maxLife > 0.0f ? bullet.life / bullet.maxLife : 0.0f;
        out[cursor + 5] = std::atan2(bullet.velocity.y, bullet.velocity.x) * to_degrees;
        out[cursor + 6] = bullet.fromPlayer ? 1.0f : 0.0f;
        cursor += bullet_floats;
        ++activeBullets;
    }

    cursor = header_floats + max_bullets * bullet_floats;
    for (const Bot& bot : bots_) {
        if (!bot.active) continue;
        out[cursor + 0] = bot.position.x;
        out[cursor + 1] = bot.position.y;
        out[cursor + 2] = bot.heading * to_degrees;
        out[cursor + 3] = bot.turret * to_degrees;
        out[cursor + 4] = bot.maxHealth > 0.0f ? bot.health / bot.maxHealth : 0.0f;
        out[cursor + 5] = static_cast<float>(bot.tankId);
        out[cursor + 6] = bot.scale;
        out[cursor + 7] = bot.flash;
        out[cursor + 8] = static_cast<float>(bot.team);
        out[cursor + 9] = static_cast<float>(bot.level);
        out[cursor + 10] = bot.recoil;
        cursor += bot_floats;
        ++activeBots;
    }

    cursor = header_floats + max_bullets * bullet_floats + max_bots * bot_floats;
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

    cursor = header_floats + max_bullets * bullet_floats + max_bots * bot_floats +
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

    const TankDefinition& definition = tankDefinition(player_.tankId);
    std::array<std::int32_t, max_children> options{};
    std::int32_t optionCount = 0;
    for (std::int32_t i = 0; i < definition.childCount; ++i) {
        const std::int32_t child = definition.children[static_cast<std::size_t>(i)];
        if (tankDefinition(child).unlockLevel > player_.level) continue;
        options[static_cast<std::size_t>(optionCount)] = child;
        ++optionCount;
    }

    out[0] = player_.position.x;
    out[1] = player_.position.y;
    out[2] = player_.heading * to_degrees;
    out[3] = player_.turret * to_degrees;
    out[4] = lengthOf(player_.velocity);
    out[5] = player_.health;
    out[6] = player_.maxHealth;
    out[7] = player_.xp;
    out[8] = experienceForLevel(player_.level);
    out[9] = static_cast<float>(player_.level);
    out[10] = player_.score;
    out[11] = cameraZoom_;
    out[12] = arenaHalfWidth_;
    out[13] = arenaHalfHeight_;
    out[14] = player_.alive ? 1.0f : 0.0f;
    out[15] = player_.respawnTimer;
    out[16] = static_cast<float>(player_.statPoints);
    out[17] = static_cast<float>(player_.tankId);
    out[18] = static_cast<float>(activeBullets);
    out[19] = static_cast<float>(activeBots);
    out[20] = static_cast<float>(activeShapes);
    out[21] = static_cast<float>(activeParticles);
    out[22] = player_.recoil;
    out[23] = player_.muzzleFlash;
    out[24] = player_.damageFlash;
    out[25] = static_cast<float>(player_.kills);
    out[26] = time_;
    out[27] = static_cast<float>(mode_);
    out[28] = teamScore_[0];
    out[29] = teamScore_[1];
    out[30] = matchTimer_;
    out[31] = matchOver_ ? 1.0f : 0.0f;
    out[32] = static_cast<float>(winner_);
    out[33] = static_cast<float>(wave_);
    out[34] = optionCount > 0 ? 1.0f : 0.0f;
    out[35] = static_cast<float>(optionCount);
    out[36] = static_cast<float>(options[0]);
    out[37] = static_cast<float>(options[1]);
    out[38] = static_cast<float>(options[2]);
    out[39] = static_cast<float>(options[3]);
    out[40] = player_.stealth;
    for (std::size_t i = 0; i < upgrade_count; ++i) {
        out[41 + i] = static_cast<float>(upgrades_[i]);
    }
    out[47] = player_.spawnGuard;
    out[48] = static_cast<float>(tank_count);
    out[49] = static_cast<float>(engine_api);
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
        static_cast<jint>(omni::tank::max_bots),
        static_cast<jint>(omni::tank::bot_floats),
        static_cast<jint>(omni::tank::max_shapes),
        static_cast<jint>(omni::tank::shape_floats),
        static_cast<jint>(omni::tank::max_particles),
        static_cast<jint>(omni::tank::particle_floats),
        static_cast<jint>(omni::tank::tank_count),
        static_cast<jint>(omni::tank::upgrade_count),
        static_cast<jint>(omni::tank::upgrade_cap),
        static_cast<jint>(omni::tank::cheat_toggle_count),
        static_cast<jint>(omni::tank::cheat_action_count),
        static_cast<jint>(omni::tank::max_barrels),
        static_cast<jint>(omni::tank::barrel_export_floats)
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
    if (env->GetArrayLength(out) < static_cast<jsize>(omni::tank::snapshot_floats)) return JNI_FALSE;

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
Java_com_omni_tank_Engine_nativeStartMatch(JNIEnv*, jobject, jint mode, jint tankId) {
    std::scoped_lock lock(gMutex);
    gSimulation.startMatch(mode, tankId);
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

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetInput(JNIEnv*, jobject, jfloat moveX, jfloat moveY,
                                         jfloat aimX, jfloat aimY, jboolean firing) {
    std::scoped_lock lock(gMutex);
    gSimulation.setInput(moveX, moveY, aimX, aimY, firing == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetGraphicsQuality(JNIEnv*, jobject, jint level) {
    std::scoped_lock lock(gMutex);
    gSimulation.setGraphicsQuality(level);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetCheat(JNIEnv*, jobject, jint index, jboolean enabled) {
    std::scoped_lock lock(gMutex);
    gSimulation.setCheat(index, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeCheatAction(JNIEnv*, jobject, jint index) {
    std::scoped_lock lock(gMutex);
    gSimulation.cheatAction(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_tank_Engine_nativeUpgrade(JNIEnv*, jobject, jint stat) {
    std::scoped_lock lock(gMutex);
    return gSimulation.upgrade(stat) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_tank_Engine_nativeEvolve(JNIEnv*, jobject, jint tankId) {
    std::scoped_lock lock(gMutex);
    return gSimulation.evolve(tankId) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omni_tank_Engine_nativeGetTankName(JNIEnv* env, jobject, jint id) {
    const omni::tank::TankDefinition& definition = omni::tank::tankDefinition(id);
    std::array<char, 64> buffer{};
    const std::size_t length = std::min(definition.name.size(), buffer.size() - 1);
    for (std::size_t i = 0; i < length; ++i) buffer[i] = definition.name[i];
    return env->NewStringUTF(buffer.data());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_omni_tank_Engine_nativeGetTankInfo(JNIEnv* env, jobject, jint id) {
    const omni::tank::TankDefinition& definition = omni::tank::tankDefinition(id);
    const jint values[] = {
        definition.tier,
        definition.unlockLevel,
        definition.barrelCount,
        definition.childCount,
        definition.children[0],
        definition.children[1],
        definition.children[2],
        definition.children[3]
    };

    constexpr jsize count = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jintArray result = env->NewIntArray(count);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omni_tank_Engine_nativeGetTankStats(JNIEnv* env, jobject, jint id) {
    const omni::tank::TankDefinition& definition = omni::tank::tankDefinition(id);
    float sustained = 0.0f;
    for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
        sustained += definition.barrels[static_cast<std::size_t>(i)].damage;
    }

    const jfloat values[] = {
        definition.damage * sustained * definition.reload,
        definition.reload,
        definition.bulletSpeed,
        definition.moveSpeed,
        definition.maxHealth,
        definition.bodyDamage,
        definition.bulletSpeed * definition.bulletLife,
        definition.stealth,
        static_cast<float>(definition.barrelCount)
    };

    constexpr jsize count = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jfloatArray result = env->NewFloatArray(count);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omni_tank_Engine_nativeGetTankGeometry(JNIEnv* env, jobject, jint id) {
    const omni::tank::TankDefinition& definition = omni::tank::tankDefinition(id);
    constexpr jsize stride = static_cast<jsize>(omni::tank::barrel_export_floats);
    constexpr jsize count = 1 + static_cast<jsize>(omni::tank::max_barrels) * stride;
    jfloat values[count]{};

    values[0] = static_cast<jfloat>(definition.barrelCount);
    for (std::int32_t i = 0; i < definition.barrelCount; ++i) {
        const omni::tank::Barrel& barrel = definition.barrels[static_cast<std::size_t>(i)];
        const jsize base = 1 + static_cast<jsize>(i) * stride;
        values[base + 0] = barrel.angle * 57.2957795f;
        values[base + 1] = barrel.length;
        values[base + 2] = barrel.width;
        values[base + 3] = barrel.offset;
        values[base + 4] = barrel.damage;
        values[base + 5] = barrel.speed;
        values[base + 6] = barrel.spread;
        values[base + 7] = barrel.delay;
        values[base + 8] = barrel.recoilOnly;
    }

    jfloatArray result = env->NewFloatArray(count);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omni_tank_Engine_nativeGetEngineInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF("Omni Tank Native Engine | C++26 | API 7 | 26 hulls | team AI");
}
