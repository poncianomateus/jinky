// A game the TV started itself from Jinky's library, followed through Steam: is it on screen yet,
// is something waiting for the player (a launcher, a Steam prompt), has it been closed?
#pragma once

#include <chrono>
#include <cstdint>

namespace tcl {

class SteamGameWatch {
public:
    enum class Event { None, Ready, NeedsAttention, Closed };

    void reset();
    // Call about once a second during a game session.
    //  Ready  (once): the game is in front, so the TV can reveal the picture.
    //  NeedsAttention (once): a launcher or prompt has been waiting in front for a few seconds;
    //                 the TV shows it so the player can answer with the controller.
    //  Closed (once): the game was running and has been gone for 10 s; the session is over.
    Event poll();

    // The Steam app running now (0 = none), as Steam itself reports it.
    static uint32_t running_app_id();
    // Closes the game in front (only a process installed by Steam). Returns true if it did.
    static bool close_foreground_game();

private:
    static bool game_in_front();
    // A window in front that isn't Big Picture, the desktop or Jinky (launcher, Steam dialog...).
    static void* prompt_in_front();

    bool started_ = false;
    bool ready_ = false;
    bool attention_ = false;
    void* prompt_ = nullptr;  // the waiting window and since when it has been in front
    std::chrono::steady_clock::time_point began_{}, running_since_{}, gone_since_{}, prompt_since_{};
};

}  // namespace tcl
