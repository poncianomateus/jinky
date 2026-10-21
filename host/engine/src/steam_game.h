// A game the TV started itself (Jinky's own library instead of Steam Big Picture), followed
// through Steam: is it on screen yet, has it been closed?
#pragma once

#include <chrono>
#include <cstdint>

namespace tcl {

class SteamGameWatch {
public:
    enum class Event { None, Ready, Closed };

    void reset();
    // Call about once a second during a game session.
    //  Ready  (once): the game is in front, so the TV can reveal the picture.
    //  Closed (once): the game was running and has been gone for 3 s; the session is over.
    Event poll();

    // The Steam app running now (0 = none), as Steam itself reports it.
    static uint32_t running_app_id();
    // Closes the game in front (only a process installed by Steam). Returns true if it did.
    static bool close_foreground_game();

private:
    static bool game_in_front();

    bool started_ = false;
    bool ready_ = false;
    std::chrono::steady_clock::time_point began_{}, running_since_{}, gone_since_{};
};

}  // namespace tcl
