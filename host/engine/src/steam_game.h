// A game the TV started itself from Jinky's library, followed on the PC: is it on screen yet, is
// something waiting for the player (a launcher, a Steam prompt), has it been closed? Works for
// Steam games and for the "non-Steam games" added to the Steam library (emulators, other stores).
#pragma once

#include <chrono>
#include <cstdint>

namespace tcl {

class SteamGameWatch {
public:
    enum class Event { None, Ready, NeedsAttention, Closed };

    void reset();
    // The game was just asked to start (Big Picture is up, or the wait for it ran out). Windows
    // in front only count as "waiting for the player" from a few seconds after this: before it,
    // Steam's own windows are still opening and nothing waits for anyone.
    void launched();
    // Call about once a second during a game session.
    //  Ready  (once): the game is in front, so the TV can reveal the picture.
    //  NeedsAttention (once): a launcher or prompt has been waiting in front for a few seconds;
    //                 the TV shows it so the player can answer with the controller.
    //  Closed (once): the game was running and is gone (right away after close_game(); after a few
    //                 seconds when it exits on its own, in case a launcher hands over); the
    //                 session is over.
    Event poll();

    // A window waits for the player in front of a game that isn't on screen yet (NeedsAttention
    // was sent): the controller then works as mouse/keyboard for it (PadMouse).
    bool waiting_for_player() const { return attention_ && !ready_; }

    // The Steam app running now (0 = none), as Steam itself reports it.
    static uint32_t running_app_id();
    // Quits the game (the one being followed, else the full-screen program in front; never Steam,
    // the desktop or Jinky). Returns true if it did. Repeated requests (the TV says it thrice)
    // are ignored while the game is closing.
    bool close_game();
    // The game's process (the one followed, else the full-screen program in front), 0 if none.
    uint32_t current_pid() const;

private:
    // The full-screen program in front, if it looks like a game (not Steam, the desktop, Jinky).
    static uint32_t game_in_front();
    // A window in front that isn't Big Picture, the desktop or Jinky (launcher, Steam dialog...).
    static void* prompt_in_front();
    static bool alive(uint32_t pid);
    // Whether a process may be the game: a Steam library game, or started during this session.
    bool could_be_the_game(uint32_t pid) const;
    // game_in_front(), if it could be the game.
    uint32_t game_candidate() const;

    bool started_ = false;
    bool ready_ = false;
    bool attention_ = false;
    bool closing_ = false;    // close_game() ended it: don't wait for Steam to notice
    uint32_t game_pid_ = 0;   // the game's process once it was seen full screen
    uint64_t began_wall_ = 0;  // session start as FILETIME (processes older than this aren't the game)
    void* prompt_ = nullptr;  // the waiting window and since when it has been in front
    std::chrono::steady_clock::time_point began_{}, launched_{}, running_since_{}, gone_since_{}, prompt_since_{};
};

}  // namespace tcl
