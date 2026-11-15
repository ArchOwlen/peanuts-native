# PEANUTS: new file (Metroid Dread 60 fps cutscenes, the rewriter's frame step state page; see this game's README, "Frame rate")
# Metroid Dread (010093801237C000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a module's NSO build ID;
# CUTS, OBS and FRAME_STEP as described there ("Registered patches"). Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py
from nso_asm import ADRP, B

# 2.1.0 (v327680) main 646761f6 43afebb3. The per-frame function main+0xd40 takes this frame's time step from one of two sources, chosen by the predicate main+0x119a320
# (called at main+0xdc0 and again at main+0xdfc): true while a cutscene runs ([ctx+0xb12] set, scene state [[ctx+0x58]+0x4b0] == 2, the scene on the list), the time since the
# cutscene's audio clock last moved (main+0x119a3d0, the 30 Hz sound thread's play position); false, the game clock's frame time as in gameplay. With the audio clock, a frame
# whose clock has not moved (a step under 1e-4 s) returns at once (main+0xe10..+0xe14 to main+0x1364) with nothing drawn: cutscenes draw at the sound thread's 30 Hz. The community's
# 60 fps patch (theboy181, IPSwitch 60fps.pchtxt for this build; addresses shifted by the NSO header: 0x119A320 is main+0x119a320) makes the predicate return false (a `ret` at its first
# instruction leaves w0 = the context pointer, bit 0 clear). Its other two edits do nothing here: main+0x15943c (b.eq → b in the SetSwapInterval setter, main+0x159420, whose only caller
# main+0x1d7430 picks interval 2 or 1 from a flag) keeps the interval at its first value, and the host never follows an interval 2 under this profile's frame step; main+0x159280 already
# holds the `mov w1,#1` it writes. The stub below is the predicate's `ret` behind the state page's target (hle_fpslock.c dm_fps_step_fold, loader.c dm_fps_state_apply): 60 returns false,
# 0 and 30 run the original (cap 0 = the game's own cutscenes at the audio clock's 30; a cap of 30 presents at 30 everywhere, hle_nvn.c fps_step_cap). x16 and x17 are dead at the
# function's entry. The stub's first instruction replaces `sub sp, sp, #0x140` and runs it itself on the original path
def frame_step(ctx):
    here, emit, fs_site, fs_put, FS_STATE_OFF = ctx.here, ctx.emit, ctx.site, ctx.put, ctx.state
    fs_site(0x119a320, 'ff0305d1')
    start = here(); emit(ADRP(16, here(), FS_STATE_OFF))
    for w in (0xb9400211,   # ldr w17,[x16]            target fps (float bits; 0 = off)
              0x52a84e10,   # movz w16,#0x4270,lsl #16 60.0f
              0x6b10023f,   # cmp w17,w16
              0x54000061):  # b.ne orig (+3)
        emit(w)
    emit(0x2a1f03e0)        # mov w0,wzr               not in a cutscene's audio clock: the game clock's step
    emit(0xd65f03c0)        # ret
    emit(0xd10503ff)        # orig: sub sp,sp,#0x140
    emit(B(here(), 0x119a324))
    fs_put(0x119a320, [B(0x119a320, start)], 'cutscene frame time: the audio clock only below a 60 fps target (state page target fps; 0 and 30 = the original predicate)')

FRAME_STEP = {'646761f643afebb379edd5e6a5151af2': frame_step}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': 'cutscene frame time from the game clock at a 60 fps target', 'now': 'build', 'goal': 'unreviewed', 'purpose': 'frame rate',
     'why': "the engine's per-frame function picks the cutscene's audio clock over the game clock by a predicate at a fixed offset"},
]
