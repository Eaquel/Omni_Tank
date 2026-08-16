#include "Tank.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <jni.h>
#include <mutex>

namespace omni::tank {

TankSimulation::TankSimulation() noexcept {
    reset();
}

void TankSimulation::reset() noexcept {
    state_ = {};
    state_.energy = 100.0f;
    state_.health = 100.0f;
    state_.cameraZoom = 1.0f;
    abilityCooldown_ = 0.0f;
    abilityEffect_ = 0.0f;
    for (std::size_t i = 0; i < state_.entities.size(); ++i) {
        const float phase = static_cast<float>(i) * 0.92f;
        state_.entities[i] = {
            std::cos(phase) * (220.0f + static_cast<float>(i) * 34.0f),
            std::sin(phase) * (180.0f + static_cast<float>(i) * 24.0f),
            phase,
            0.82f,
            true
        };
    }
}

void TankSimulation::step(float dt) noexcept {
    dt = std::clamp(dt, 0.0f, 0.05f);
    state_.animationTime += dt;
    abilityCooldown_ = std::max(0.0f, abilityCooldown_ - dt);
    abilityEffect_ = std::max(0.0f, abilityEffect_ - dt);
    const float t = state_.animationTime;
    const float inputMagnitude = std::clamp(std::hypot(input_.moveX, input_.moveY), 0.0f, 1.0f);
    const float targetVelocity = (2.2f + static_cast<float>(mode_) * 0.18f) * inputMagnitude;
    state_.velocity += (targetVelocity - state_.velocity) * std::min(1.0f, dt * 8.0f);
    if (inputMagnitude > 0.01f) {
        const float desiredHeading = std::atan2(input_.moveY, input_.moveX);
        float delta = desiredHeading - state_.heading;
        while (delta > 3.14159265f) delta -= 6.28318531f;
        while (delta < -3.14159265f) delta += 6.28318531f;
        state_.heading += delta * std::min(1.0f, dt * 5.0f);
    } else {
        state_.heading += std::sin(t * 0.37f) * 0.006f;
    }
    state_.x += std::cos(state_.heading) * state_.velocity * dt;
    state_.y += std::sin(state_.heading) * state_.velocity * dt;
    if (std::abs(input_.aimX) + std::abs(input_.aimY) > 0.05f) {
        state_.turretAngle = std::atan2(input_.aimY, input_.aimX) - state_.heading;
    } else {
        state_.turretAngle = std::sin(t * 0.72f) * 0.42f;
    }
    state_.trackOffset = std::fmod(t * (state_.velocity + 0.2f) * 0.65f, 1.0f);
    state_.energy = developerFlags_[1] ? 100.0f : 96.0f + std::sin(t * 0.23f) * 4.0f;
    state_.health = developerFlags_[0] ? 100.0f : 94.0f + std::sin(t * 0.17f) * 6.0f;
    state_.cameraZoom = 1.0f + std::sin(t * 0.21f) * 0.025f;
    state_.shieldPulse = abilityEffect_ > 0.0f ? 0.65f + std::sin(t * 5.0f) * 0.35f : 0.0f;
    state_.abilityPulse = abilityCooldown_ > 0.0f ? 1.0f - std::clamp(abilityCooldown_ / 8.0f, 0.0f, 1.0f) : 1.0f;

    for (std::size_t i = 0; i < state_.entities.size(); ++i) {
        auto& entity = state_.entities[i];
        const float phase = t * (0.18f + static_cast<float>(i) * 0.015f) + static_cast<float>(i) * 0.92f;
        entity.x = std::cos(phase) * (220.0f + static_cast<float>(i) * 34.0f);
        entity.y = std::sin(phase) * (180.0f + static_cast<float>(i) * 24.0f);
        entity.rotation = phase + 1.2f;
        entity.scale = 0.82f + std::sin(t * 1.4f + static_cast<float>(i)) * 0.08f;
        entity.active = true;
    }

    if (graphicsQuality_ >= 2) {
        for (std::size_t i = 0; i < state_.particles.size(); ++i) {
            auto& particle = state_.particles[i];
            const float phase = t * (0.5f + static_cast<float>(i % 5) * 0.09f) + static_cast<float>(i) * 0.37f;
            particle.x = state_.x * 10.0f + std::cos(phase) * (18.0f + static_cast<float>(i) * 2.0f);
            particle.y = state_.y * 10.0f + std::sin(phase) * (18.0f + static_cast<float>(i) * 1.7f);
            particle.alpha = 0.2f + 0.8f * (std::sin(phase * 1.7f) + 1.0f) * 0.5f;
        }
    } else {
        for (auto& particle : state_.particles) particle.alpha = 0.0f;
    }
}

void TankSimulation::setMode(std::int32_t mode) noexcept {
    mode_ = std::clamp(mode, 0, 4);
}

void TankSimulation::setGraphicsQuality(std::int32_t level) noexcept {
    graphicsQuality_ = std::clamp(level, 0, 4);
}

void TankSimulation::setDeveloperFlag(std::int32_t flag, bool enabled) noexcept {
    if (flag >= 0 && flag < static_cast<std::int32_t>(developerFlags_.size())) developerFlags_[static_cast<std::size_t>(flag)] = enabled;
}

void TankSimulation::setInput(float moveX, float moveY, float aimX, float aimY) noexcept {
    input_.moveX = std::clamp(moveX, -1.0f, 1.0f);
    input_.moveY = std::clamp(moveY, -1.0f, 1.0f);
    input_.aimX = std::clamp(aimX, -1.0f, 1.0f);
    input_.aimY = std::clamp(aimY, -1.0f, 1.0f);
}

void TankSimulation::triggerAbility(std::int32_t index) noexcept {
    if (index < 0 || index > 3 || abilityCooldown_ > 0.0f) return;
    abilityCooldown_ = 8.0f + static_cast<float>(index) * 2.0f;
    abilityEffect_ = index == 0 ? 4.0f : 2.5f;
    if (index == 2) state_.health = std::min(100.0f, state_.health + 18.0f);
    if (index == 1) state_.velocity += 0.9f;
}

const SimulationState& TankSimulation::state() const noexcept {
    return state_;
}

}

namespace {
std::mutex gMutex;
omni::tank::TankSimulation gSimulation;

jfloatArray snapshot(JNIEnv* env) {
    const auto& state = gSimulation.state();
    constexpr int base = 12;
    constexpr int entityCount = static_cast<int>(omni::tank::SimulationState::entity_count);
    constexpr int particleCount = static_cast<int>(omni::tank::SimulationState::particle_count);
    constexpr int count = base + entityCount * 5 + particleCount * 3;
    std::array<jfloat, count> values{};
    values[0] = state.x;
    values[1] = state.y;
    values[2] = state.heading * 57.2957795f;
    values[3] = state.turretAngle * 57.2957795f;
    values[4] = state.velocity;
    values[5] = state.animationTime;
    values[6] = state.trackOffset;
    values[7] = state.energy;
    values[8] = state.health;
    values[9] = state.cameraZoom;
    values[10] = state.shieldPulse;
    values[11] = state.abilityPulse;
    int offset = base;
    for (const auto& entity : state.entities) {
        values[offset++] = entity.x;
        values[offset++] = entity.y;
        values[offset++] = entity.rotation;
        values[offset++] = entity.scale;
        values[offset++] = entity.active ? 1.0f : 0.0f;
    }
    for (const auto& particle : state.particles) {
        values[offset++] = particle.x;
        values[offset++] = particle.y;
        values[offset++] = particle.alpha;
    }
    auto* result = env->NewFloatArray(count);
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, count, values.data());
    return result;
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omni_tank_Engine_nativeStep(JNIEnv* env, jobject, jfloat dt) {
    std::scoped_lock lock(gMutex);
    gSimulation.step(dt);
    return snapshot(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeReset(JNIEnv*, jobject) {
    std::scoped_lock lock(gMutex);
    gSimulation.reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omni_tank_Engine_nativeGetEngineInfo(JNIEnv* env, jobject) {
    constexpr auto info = "Omni Tank Native Engine | C++26 | API 3 | Simulation Animation Input Modes";
    return env->NewStringUTF(info);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeSetMode(JNIEnv*, jobject, jint mode) {
    std::scoped_lock lock(gMutex);
    gSimulation.setMode(mode);
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
Java_com_omni_tank_Engine_nativeSetInput(JNIEnv*, jobject, jfloat moveX, jfloat moveY, jfloat aimX, jfloat aimY) {
    std::scoped_lock lock(gMutex);
    gSimulation.setInput(moveX, moveY, aimX, aimY);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omni_tank_Engine_nativeTriggerAbility(JNIEnv*, jobject, jint index) {
    std::scoped_lock lock(gMutex);
    gSimulation.triggerAbility(index);
}
