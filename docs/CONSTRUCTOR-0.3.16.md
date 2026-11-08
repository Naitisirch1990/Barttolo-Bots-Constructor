# Constructor 0.3.16

- Tunnel ceiling lights are now spaced five blocks apart. Other road and bridge light spacing remains ten blocks.
- Replaceable block entities such as mine chests and spawners no longer invalidate a straight corridor.
- Replaced block entity data and inventories are journaled and restored by undo. Block entities are removed before replacement to prevent duplicate inventory drops.
- An unbreakable block in the direct corridor stops planning immediately with its block identifier and exact position.
- Tests cover a straight 75-block tunnel at Y=31 through a chest and spawner, inventory/spawner restoration, five-block light spacing, and prompt bedrock diagnostics.
