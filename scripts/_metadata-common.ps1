# Shared helpers for the metadata schema capture and validation scripts.
# Dot-sourced so the capture run and the through-Conduit run summon exactly
# the same entities; a difference in the summon list would make the two
# schemas incomparable, which is the whole point of the comparison.

function Kill-Stale {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
    if ($_.CommandLine -match "AllTests|GameplayProbe|SchemaProbe|conduit\.launcher|net\.minecraft|server\.jar") {
      Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  }
  Start-Sleep 3
}

function Wait-PortListen([int]$Port, [int]$Seconds = 180) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return }
    Start-Sleep 1
  }
  throw "port $Port never listened"
}

function Start-Backend([string]$Dir, [int]$Port, [string]$Label, [string]$OutDir, [string]$Stamp, [string]$Java8) {
  # The probe reads raw frames, so compression has to be off or every packet
  # after the threshold arrives as an unreadable deflate blob.
  @(
    "online-mode=false"
    "server-port=$Port"
    "gamemode=1"
    "force-gamemode=true"
    "difficulty=peaceful"
    "spawn-protection=0"
    "level-name=world"
    "network-compression-threshold=-1"
  ) | Set-Content (Join-Path $Dir "server.properties")
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $Java8
  $psi.Arguments = "-Xms512M -Xmx1G -jar server.jar nogui"
  $psi.WorkingDirectory = $Dir
  $psi.UseShellExecute = $false
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $proc = [System.Diagnostics.Process]::Start($psi)
  $log = Join-Path $OutDir "$Label-$Stamp.log"
  Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
  } -MessageData $log | Out-Null
  $proc.BeginOutputReadLine()
  Wait-PortListen $Port
  return @{ Proc = $proc; Log = $log }
}

function Send-Console($backend, [string]$Command) {
  $backend.Proc.StandardInput.WriteLine($Command)
  $backend.Proc.StandardInput.Flush()
  Start-Sleep -Milliseconds 500
}

# Every entity type in the 1.13.2 registry, so the captured schema covers the
# families the translator can actually meet rather than a hand-picked sample.
# Both servers get the identical list; anything 1.14 renamed simply fails to
# summon there and is absent from that side, which is itself the answer.
function Summon-All($backend, [string]$User) {
  Send-Console $backend "gamemode creative $User"
  Send-Console $backend "difficulty peaceful"
  Send-Console $backend "time set day"
  Send-Console $backend "gamerule doMobSpawning false"
  Send-Console $backend "summon minecraft:area_effect_cloud ~-4 ~ ~3"
  Send-Console $backend "summon minecraft:armor_stand ~-3 ~ ~3"
  Send-Console $backend "summon minecraft:arrow ~-2 ~ ~3"
  Send-Console $backend "summon minecraft:bat ~-1 ~ ~3"
  Send-Console $backend "summon minecraft:blaze ~0 ~ ~3"
  Send-Console $backend "summon minecraft:boat ~1 ~ ~3"
  Send-Console $backend "summon minecraft:cave_spider ~2 ~ ~3"
  Send-Console $backend "summon minecraft:chicken ~3 ~ ~3"
  Send-Console $backend "summon minecraft:cod ~-4 ~ ~4"
  Send-Console $backend "summon minecraft:cow ~-3 ~ ~4"
  Send-Console $backend "summon minecraft:creeper ~-2 ~ ~4"
  Send-Console $backend "summon minecraft:donkey ~-1 ~ ~4"
  Send-Console $backend "summon minecraft:dolphin ~0 ~ ~4"
  Send-Console $backend "summon minecraft:dragon_fireball ~1 ~ ~4"
  Send-Console $backend "summon minecraft:drowned ~2 ~ ~4"
  Send-Console $backend "summon minecraft:elder_guardian ~3 ~ ~4"
  Send-Console $backend "summon minecraft:end_crystal ~-4 ~ ~5"
  Send-Console $backend "summon minecraft:ender_dragon ~-3 ~ ~5"
  Send-Console $backend "summon minecraft:enderman ~-2 ~ ~5"
  Send-Console $backend "summon minecraft:endermite ~-1 ~ ~5"
  Send-Console $backend "summon minecraft:evoker_fangs ~0 ~ ~5"
  Send-Console $backend "summon minecraft:evoker ~1 ~ ~5"
  Send-Console $backend "summon minecraft:experience_orb ~2 ~ ~5"
  Send-Console $backend "summon minecraft:eye_of_ender ~3 ~ ~5"
  Send-Console $backend "summon minecraft:falling_block ~-4 ~ ~6"
  Send-Console $backend "summon minecraft:firework_rocket ~-3 ~ ~6"
  Send-Console $backend "summon minecraft:ghast ~-2 ~ ~6"
  Send-Console $backend "summon minecraft:giant ~-1 ~ ~6"
  Send-Console $backend "summon minecraft:guardian ~0 ~ ~6"
  Send-Console $backend "summon minecraft:horse ~1 ~ ~6"
  Send-Console $backend "summon minecraft:husk ~2 ~ ~6"
  Send-Console $backend "summon minecraft:illusioner ~3 ~ ~6"
  Send-Console $backend "summon minecraft:item ~-4 ~ ~7"
  Send-Console $backend "summon minecraft:item_frame ~-3 ~ ~7"
  Send-Console $backend "summon minecraft:fireball ~-2 ~ ~7"
  Send-Console $backend "summon minecraft:leash_knot ~-1 ~ ~7"
  Send-Console $backend "summon minecraft:llama ~0 ~ ~7"
  Send-Console $backend "summon minecraft:llama_spit ~1 ~ ~7"
  Send-Console $backend "summon minecraft:magma_cube ~2 ~ ~7"
  Send-Console $backend "summon minecraft:minecart ~3 ~ ~7"
  Send-Console $backend "summon minecraft:chest_minecart ~-4 ~ ~8"
  Send-Console $backend "summon minecraft:command_block_minecart ~-3 ~ ~8"
  Send-Console $backend "summon minecraft:furnace_minecart ~-2 ~ ~8"
  Send-Console $backend "summon minecraft:hopper_minecart ~-1 ~ ~8"
  Send-Console $backend "summon minecraft:spawner_minecart ~0 ~ ~8"
  Send-Console $backend "summon minecraft:tnt_minecart ~1 ~ ~8"
  Send-Console $backend "summon minecraft:mule ~2 ~ ~8"
  Send-Console $backend "summon minecraft:mooshroom ~3 ~ ~8"
  Send-Console $backend "summon minecraft:ocelot ~-4 ~ ~9"
  Send-Console $backend "summon minecraft:painting ~-3 ~ ~9"
  Send-Console $backend "summon minecraft:parrot ~-2 ~ ~9"
  Send-Console $backend "summon minecraft:pig ~-1 ~ ~9"
  Send-Console $backend "summon minecraft:pufferfish ~0 ~ ~9"
  Send-Console $backend "summon minecraft:zombie_pigman ~1 ~ ~9"
  Send-Console $backend "summon minecraft:polar_bear ~2 ~ ~9"
  Send-Console $backend "summon minecraft:tnt ~3 ~ ~9"
  Send-Console $backend "summon minecraft:rabbit ~-4 ~ ~10"
  Send-Console $backend "summon minecraft:salmon ~-3 ~ ~10"
  Send-Console $backend "summon minecraft:sheep ~-2 ~ ~10"
  Send-Console $backend "summon minecraft:shulker ~-1 ~ ~10"
  Send-Console $backend "summon minecraft:shulker_bullet ~0 ~ ~10"
  Send-Console $backend "summon minecraft:silverfish ~1 ~ ~10"
  Send-Console $backend "summon minecraft:skeleton ~2 ~ ~10"
  Send-Console $backend "summon minecraft:skeleton_horse ~3 ~ ~10"
  Send-Console $backend "summon minecraft:slime ~-4 ~ ~3"
  Send-Console $backend "summon minecraft:small_fireball ~-3 ~ ~3"
  Send-Console $backend "summon minecraft:snow_golem ~-2 ~ ~3"
  Send-Console $backend "summon minecraft:snowball ~-1 ~ ~3"
  Send-Console $backend "summon minecraft:spectral_arrow ~0 ~ ~3"
  Send-Console $backend "summon minecraft:spider ~1 ~ ~3"
  Send-Console $backend "summon minecraft:squid ~2 ~ ~3"
  Send-Console $backend "summon minecraft:stray ~3 ~ ~3"
  Send-Console $backend "summon minecraft:tropical_fish ~-4 ~ ~4"
  Send-Console $backend "summon minecraft:turtle ~-3 ~ ~4"
  Send-Console $backend "summon minecraft:egg ~-2 ~ ~4"
  Send-Console $backend "summon minecraft:ender_pearl ~-1 ~ ~4"
  Send-Console $backend "summon minecraft:experience_bottle ~0 ~ ~4"
  Send-Console $backend "summon minecraft:potion ~1 ~ ~4"
  Send-Console $backend "summon minecraft:vex ~2 ~ ~4"
  Send-Console $backend "summon minecraft:villager ~3 ~ ~4"
  Send-Console $backend "summon minecraft:iron_golem ~-4 ~ ~5"
  Send-Console $backend "summon minecraft:vindicator ~-3 ~ ~5"
  Send-Console $backend "summon minecraft:witch ~-2 ~ ~5"
  Send-Console $backend "summon minecraft:wither ~-1 ~ ~5"
  Send-Console $backend "summon minecraft:wither_skeleton ~0 ~ ~5"
  Send-Console $backend "summon minecraft:wither_skull ~1 ~ ~5"
  Send-Console $backend "summon minecraft:wolf ~2 ~ ~5"
  Send-Console $backend "summon minecraft:zombie ~3 ~ ~5"
  Send-Console $backend "summon minecraft:zombie_horse ~-4 ~ ~6"
  Send-Console $backend "summon minecraft:zombie_villager ~-3 ~ ~6"
  Send-Console $backend "summon minecraft:phantom ~-2 ~ ~6"
  Send-Console $backend "summon minecraft:trident ~-1 ~ ~6"
  Send-Console $backend "summon minecraft:item ~ ~ ~9 {Item:{id:`"minecraft:diamond`",Count:1b}}"
  Send-Console $backend "effect give @e minecraft:glowing 60 1"
  Start-Sleep 3
}

