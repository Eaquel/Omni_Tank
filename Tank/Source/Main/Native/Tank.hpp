#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace omni::tank {

inline constexpr std::size_t max_bullets = 96;
inline constexpr std::size_t max_enemies = 24;
inline constexpr std::size_t max_shapes = 48;
inline constexpr std::size_t max_particles = 160;
inline constexpr std::size_t upgrade_count = 6;
inline constexpr std::size_t ability_count = 4;
inline constexpr std::size_t developer_flag_count = 3;

// Snapshot layout shared with the Kotlin renderer. The renderer reads the
// strides through nativeGetLayout() so the two sides can never drift apart.
inline constexpr std::size_t header_floats = 40;
inline constexpr std::size_t bullet_floats = 6;
inline constexpr std::size_t enemy_floats = 8;
inline constexpr std::size_t shape_floats = 7;
inline constexpr std::size_t particle_floats = 6;

inline constexpr std::size_t snapshot_floats =
    header_floats +
    max_bullets * bullet_floats +
    max_enemies * enemy_floats +
    max_shapes * shape_floats +
    max_particles * particle_floats;

struct Vec2 {
    float x{};
    float y{};
};

struct Bullet {
    Vec2 position{};
    Vec2 velocity{};
    float radius{7.0f};
    float life{};
    float maxLife{1.0f};
    float damage{};
    std::int32_t owner{};
    bool active{};
};

struct Enemy {
    Vec2 position{};
    Vec2 velocity{};
    float heading{};
    float turret{};
    float health{};
    float maxHealth{1.0f};
    float reload{};
    float flash{};
    float scale{1.0f};
    float wander{};
    std::int32_t kind{};
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
    float energy{100.0f};
    float maxEnergy{100.0f};
    float reload{};
    float recoil{};
    float muzzleFlash{};
    float damageFlash{};
    float score{};
    float xp{};
    float respawnTimer{};
    std::int32_t level{1};
    std::int32_t kills{};
    std::int32_t statPoints{};
    bool alive{true};
};

class TankSimulation {
public:
    TankSimulation() noexcept;

    void reset() noexcept;
    void step(float deltaSeconds) noexcept;
    void snapshot(float* out, std::size_t capacity) const noexcept;

    void setMode(std::int32_t mode) noexcept;
    void setHull(std::int32_t hull) noexcept;
    void setGraphicsQuality(std::int32_t level) noexcept;
    void setDeveloperFlag(std::int32_t flag, bool enabled) noexcept;
    void setInput(float moveX, float moveY, float aimX, float aimY, bool firing) noexcept;
    bool triggerAbility(std::int32_t index) noexcept;
    bool upgrade(std::int32_t stat) noexcept;
    void respawn() noexcept;

    std::int32_t upgradeLevel(std::int32_t stat) const noexcept;
    std::int32_t statPoints() const noexcept;
    std::int32_t level() const noexcept;
    float abilityCooldownRatio(std::int32_t index) const noexcept;

private:
    void spawnWorld() noexcept;
    void spawnShape(std::size_t index, bool anywhere) noexcept;
    void spawnEnemy() noexcept;
    void fireBullet(Vec2 origin, float angle, float speed, float damage, float radius,
                    float life, std::int32_t owner) noexcept;
    void burst(Vec2 origin, std::int32_t kind, std::int32_t count, float speed) noexcept;
    void awardExperience(float amount, float points) noexcept;
    void damagePlayer(float amount) noexcept;
    void updatePlayer(float dt) noexcept;
    void updateEnemies(float dt) noexcept;
    void updateShapes(float dt) noexcept;
    void updateBullets(float dt) noexcept;
    void updateParticles(float dt) noexcept;
    void updateSpawning(float dt) noexcept;
    void clampToArena(Vec2& position, float radius) const noexcept;

    float random() noexcept;
    float randomRange(float low, float high) noexcept;

    float damageStat() const noexcept;
    float reloadStat() const noexcept;
    float bulletSpeedStat() const noexcept;
    float moveSpeedStat() const noexcept;
    float maxHealthStat() const noexcept;
    float regenStat() const noexcept;

    Player player_{};
    InputState input_{};
    std::array<Bullet, max_bullets> bullets_{};
    std::array<Enemy, max_enemies> enemies_{};
    std::array<Shape, max_shapes> shapes_{};
    std::array<Particle, max_particles> particles_{};
    std::array<std::int32_t, upgrade_count> upgrades_{};
    std::array<float, ability_count> abilityCooldowns_{};
    std::array<bool, developer_flag_count> developerFlags_{};

    float arenaHalfWidth_{2600.0f};
    float arenaHalfHeight_{1700.0f};
    float time_{};
    float spawnTimer_{};
    float waveTimer_{};
    float shieldTimer_{};
    float overdriveTimer_{};
    float scanTimer_{};
    float cameraZoom_{1.0f};
    float threat_{};
    std::uint32_t rng_{0x9E3779B9u};
    std::int32_t mode_{};
    std::int32_t hull_{};
    std::int32_t graphicsQuality_{3};
    std::int32_t wave_{1};
};

constexpr std::string_view engine_name = "Omni Tank Native Engine";
constexpr std::int32_t engine_api = 5;

}
