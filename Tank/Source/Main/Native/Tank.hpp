#pragma once

#include <array>
#include <cstdint>
#include <string_view>

namespace omni::tank {

struct EntityState {
    float x{};
    float y{};
    float rotation{};
    float scale{1.0f};
    bool active{true};
};

struct ParticleState {
    float x{};
    float y{};
    float alpha{};
};

struct InputState {
    float moveX{};
    float moveY{};
    float aimX{};
    float aimY{};
};

struct SimulationState {
    static constexpr std::size_t entity_count = 12;
    static constexpr std::size_t particle_count = 32;
    float x{};
    float y{};
    float heading{};
    float turretAngle{};
    float velocity{};
    float animationTime{};
    float trackOffset{};
    float energy{100.0f};
    float health{100.0f};
    float cameraZoom{1.0f};
    float shieldPulse{};
    float abilityPulse{};
    std::array<EntityState, entity_count> entities{};
    std::array<ParticleState, particle_count> particles{};
};

class TankSimulation {
public:
    TankSimulation() noexcept;
    void reset() noexcept;
    void step(float deltaSeconds) noexcept;
    void setMode(std::int32_t mode) noexcept;
    void setGraphicsQuality(std::int32_t level) noexcept;
    void setDeveloperFlag(std::int32_t flag, bool enabled) noexcept;
    void setInput(float moveX, float moveY, float aimX, float aimY) noexcept;
    void triggerAbility(std::int32_t index) noexcept;
    const SimulationState& state() const noexcept;

private:
    SimulationState state_{};
    InputState input_{};
    std::int32_t mode_{};
    std::int32_t graphicsQuality_{3};
    std::array<bool, 3> developerFlags_{};
    float abilityCooldown_{};
    float abilityEffect_{};
};

constexpr std::string_view engine_name = "Omni Tank Native Engine";
constexpr std::int32_t engine_api = 3;

}
