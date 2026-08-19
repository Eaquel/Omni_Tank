#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace omni::tank {

inline constexpr std::size_t max_bullets = 240;
inline constexpr std::size_t max_bots = 20;
inline constexpr std::size_t max_shapes = 56;
inline constexpr std::size_t max_particles = 180;
inline constexpr std::size_t max_barrels = 12;
inline constexpr std::size_t max_children = 4;
inline constexpr std::size_t tank_count = 26;
inline constexpr std::size_t upgrade_count = 6;
inline constexpr std::size_t upgrade_cap = 8;
inline constexpr std::size_t cheat_toggle_count = 18;
inline constexpr std::size_t cheat_action_count = 8;

inline constexpr std::size_t header_floats = 50;
inline constexpr std::size_t bullet_floats = 7;
inline constexpr std::size_t bot_floats = 11;
inline constexpr std::size_t shape_floats = 7;
inline constexpr std::size_t particle_floats = 6;

inline constexpr std::size_t snapshot_floats =
    header_floats +
    max_bullets * bullet_floats +
    max_bots * bot_floats +
    max_shapes * shape_floats +
    max_particles * particle_floats;

inline constexpr std::size_t barrel_export_floats = 9;

inline constexpr std::int32_t mode_survival = 0;
inline constexpr std::int32_t mode_team_battle = 1;

inline constexpr std::int32_t cheat_god = 0;
inline constexpr std::int32_t cheat_aimbot = 1;
inline constexpr std::int32_t cheat_magic_bullet = 2;
inline constexpr std::int32_t cheat_no_cooldown = 3;
inline constexpr std::int32_t cheat_one_shot = 4;
inline constexpr std::int32_t cheat_rapid_fire = 5;
inline constexpr std::int32_t cheat_giant_bullets = 6;
inline constexpr std::int32_t cheat_speed = 7;
inline constexpr std::int32_t cheat_ghost = 8;
inline constexpr std::int32_t cheat_infinite_range = 9;
inline constexpr std::int32_t cheat_no_recoil = 10;
inline constexpr std::int32_t cheat_xp_boost = 11;
inline constexpr std::int32_t cheat_slow_enemies = 12;
inline constexpr std::int32_t cheat_zoom_out = 13;
inline constexpr std::int32_t cheat_auto_fire = 14;
inline constexpr std::int32_t cheat_penetration = 15;
inline constexpr std::int32_t cheat_vampire = 16;
inline constexpr std::int32_t cheat_freeze = 17;

struct Vec2 {
    float x{};
    float y{};
};

struct Barrel {
    float angle{};
    float length{96.0f};
    float width{30.0f};
    float offset{};
    float damage{1.0f};
    float speed{1.0f};
    float spread{0.02f};
    float delay{};
    float recoilOnly{};
};

struct TankDefinition {
    std::string_view name{};
    std::int32_t tier{1};
    std::int32_t unlockLevel{1};
    float reload{1.0f};
    float damage{1.0f};
    float bulletSpeed{1.0f};
    float bulletLife{1.0f};
    float bulletRadius{1.0f};
    float moveSpeed{1.0f};
    float maxHealth{1.0f};
    float bodyDamage{1.0f};
    float vision{};
    float stealth{};
    std::int32_t barrelCount{1};
    std::int32_t childCount{};
    std::array<std::int32_t, max_children> children{};
    std::array<Barrel, max_barrels> barrels{};
};

struct Bullet {
    Vec2 position{};
    Vec2 velocity{};
    float radius{8.0f};
    float life{};
    float maxLife{1.0f};
    float damage{};
    std::int32_t team{};
    std::int32_t sourceBot{-1};
    bool fromPlayer{};
    bool homing{};
    bool penetrating{};
    bool active{};
};

struct Bot {
    Vec2 position{};
    Vec2 velocity{};
    float heading{};
    float turret{};
    float health{};
    float maxHealth{1.0f};
    float reload{};
    float flash{};
    float recoil{};
    float scale{1.0f};
    float think{};
    float wander{};
    float aggression{0.5f};
    float accuracy{0.5f};
    float retreat{};
    float respawn{};
    std::array<float, max_barrels> barrelTimers{};
    std::int32_t tankId{};
    std::int32_t team{1};
    std::int32_t level{1};
    std::int32_t target{-2};
    bool active{};
};

struct Shape {
    Vec2 position{};
    Vec2 velocity{};
    float rotation{};
    float spin{};
    float size{};
    float health{};
    float maxHealth{1.0f};
    float flash{};
    std::int32_t sides{4};
    bool active{};
};

struct Particle {
    Vec2 position{};
    Vec2 velocity{};
    float life{};
    float maxLife{1.0f};
    float size{};
    std::int32_t kind{};
    bool active{};
};

struct InputState {
    float moveX{};
    float moveY{};
    float aimX{};
    float aimY{};
    bool firing{};
};

struct Player {
    Vec2 position{};
    Vec2 velocity{};
    float heading{};
    float turret{};
    float health{100.0f};
    float maxHealth{100.0f};
    float recoil{};
    float muzzleFlash{};
    float damageFlash{};
    float xp{};
    float score{};
    float respawnTimer{};
    float spawnGuard{};
    float stealth{};
    float idle{};
    std::array<float, max_barrels> barrelTimers{};
    std::int32_t level{1};
    std::int32_t kills{};
    std::int32_t statPoints{};
    std::int32_t tankId{};
    bool alive{true};
};

const TankDefinition& tankDefinition(std::int32_t id) noexcept;
std::int32_t tierUnlockLevel(std::int32_t tier) noexcept;

class TankSimulation {
public:
    TankSimulation() noexcept;

    void startMatch(std::int32_t mode, std::int32_t tankId) noexcept;
    void reset() noexcept;
    void step(float deltaSeconds) noexcept;
    void snapshot(float* out, std::size_t capacity) const noexcept;

    void setInput(float moveX, float moveY, float aimX, float aimY, bool firing) noexcept;
    void setGraphicsQuality(std::int32_t level) noexcept;
    void setCheat(std::int32_t index, bool enabled) noexcept;
    void cheatAction(std::int32_t index) noexcept;
    bool upgrade(std::int32_t stat) noexcept;
    bool evolve(std::int32_t tankId) noexcept;
    void respawn() noexcept;

    std::int32_t upgradeLevel(std::int32_t stat) const noexcept;
    std::int32_t statPoints() const noexcept;

private:
    void spawnWorld() noexcept;
    void spawnShape(std::size_t index, bool anywhere) noexcept;
    void spawnBot(std::int32_t team, std::int32_t level, Vec2 origin) noexcept;
    void fireWeapon(Vec2 origin, float baseAngle, const TankDefinition& definition,
                    float damageScale, float speedScale, float radiusScale,
                    float lifeScale, std::int32_t team, std::int32_t sourceBot,
                    bool fromPlayer, std::array<float, max_barrels>& timers,
                    float& recoilOut, Vec2& velocityOut, float dt) noexcept;
    void burst(Vec2 origin, std::int32_t kind, std::int32_t count, float speed) noexcept;
    void awardExperience(float amount, float points) noexcept;
    void addTeamScore(std::int32_t team, float points) noexcept;
    void damagePlayer(float amount) noexcept;
    void updatePlayer(float dt) noexcept;
    void updateBots(float dt) noexcept;
    void updateShapes(float dt) noexcept;
    void updateBullets(float dt) noexcept;
    void updateParticles(float dt) noexcept;
    void updateDirector(float dt) noexcept;
    void clampToArena(Vec2& position, float radius) const noexcept;
    void resolveHit(Bullet& bullet, float damage) noexcept;
    std::int32_t pickTarget(const Bot& bot, std::size_t self) const noexcept;
    Vec2 targetPosition(std::int32_t target) const noexcept;
    Vec2 targetVelocity(std::int32_t target) const noexcept;
    bool targetAlive(std::int32_t target) const noexcept;
    std::int32_t nearestEnemyToPlayer(float maxDistance) const noexcept;
    std::int32_t rosterTank(std::int32_t level) noexcept;

    float random() noexcept;
    float randomRange(float low, float high) noexcept;

    float damageStat() const noexcept;
    float reloadStat() const noexcept;
    float bulletSpeedStat() const noexcept;
    float moveSpeedStat() const noexcept;
    float maxHealthStat() const noexcept;
    float regenStat() const noexcept;
    bool cheat(std::int32_t index) const noexcept;

    Player player_{};
    InputState input_{};
    std::array<Bullet, max_bullets> bullets_{};
    std::array<Bot, max_bots> bots_{};
    std::array<Shape, max_shapes> shapes_{};
    std::array<Particle, max_particles> particles_{};
    std::array<std::int32_t, upgrade_count> upgrades_{};
    std::array<bool, cheat_toggle_count> cheats_{};
    std::array<float, 2> teamScore_{};

    float arenaHalfWidth_{3000.0f};
    float arenaHalfHeight_{1900.0f};
    float time_{};
    float matchTimer_{};
    float spawnTimer_{};
    float waveTimer_{};
    float cameraZoom_{1.0f};
    std::uint32_t rng_{0x9E3779B9u};
    std::int32_t mode_{};
    std::int32_t graphicsQuality_{3};
    std::int32_t wave_{1};
    std::int32_t winner_{-1};
    bool matchOver_{};
};

constexpr std::string_view engine_name = "Omni Tank Native Engine";
constexpr std::int32_t engine_api = 7;

}
