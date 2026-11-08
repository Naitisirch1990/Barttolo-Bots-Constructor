# Constructor 0.3.18

- `/bot constructor continuar` and repeated `/bot constructor deshacer` resume the saved undo index instead of starting the journal again.
- Undo tolerates missing blocks, flowing liquids, vegetation and falling-block changes while retaining world-border, unbreakable-block and Forge protection checks.
- `/bot constructor deshacer forzar` explicitly restores over other changed, replaceable blocks. The forced mode and current restoration index survive restarts.
- `/bot constructor cancelar` stops the active project, keeps current blocks and archives only the still-pending undo history. New projects can then be created immediately.
- Conflict messages identify the current and expected blocks and give recovery commands.
- Tests cover saved paused-job recovery with missing blocks and water, partial undo cancellation and archive restoration, forced conflict recovery, bedrock protection and a dry tunnel built directly under a lake.
