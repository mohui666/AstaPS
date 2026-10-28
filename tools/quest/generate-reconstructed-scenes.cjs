// Run from the repository root. The JSON catalog is the editable reconstruction source.
const fs = require('fs');
const path = require('path');

const catalog = JSON.parse(fs.readFileSync(
  'src/main/resources/defaults/data/QuestStoryReconstruction.json', 'utf8'));
const root = path.resolve('resources/Scripts/Scene');
const changed = [];
const byScene = new Map();
const marker = '-- ASTAPS_STORY_RECONSTRUCTION';
const blockMarker = '-- ASTAPS_STORY_RECONSTRUCTION_BLOCK';
const luaPosition = p => `{ x = ${p.x}, y = ${p.y}, z = ${p.z} }`;

for (const group of catalog.groups) {
  if (!Number.isInteger(group.sceneId) || !Number.isInteger(group.groupId)
      || group.sceneId <= 0 || group.groupId <= 0) throw Error('Invalid group');
  const directory = path.join(root, String(group.sceneId));
  fs.mkdirSync(directory, { recursive: true });
  const file = path.join(directory, `scene${group.sceneId}_group${group.groupId}.lua`);
  let prefix = '';
  if (fs.existsSync(file)) {
    const original = fs.readFileSync(file, 'utf8');
    if (original.includes(marker)) {
      prefix = original.split(marker)[0].trimEnd();
      if (prefix) prefix += '\n\n';
    } else if (group.preserveExistingSpawnData) {
      if (!/triggers\s*=\s*\{\s*\}/.test(original))
        throw Error(`Existing business script must not be overwritten: ${file}`);
      prefix = original.trimEnd() + '\n\n';
    } else throw Error(`Unexpected existing group: ${file}`);
  }
  const definition = `${marker}\n`
    + '-- User-authorized custom server gameplay; not recovered official Lua.\n'
    + `story_reconstruction = { group_id = ${group.groupId}, suite_count = ${group.suiteCount}, `
    + `position = ${luaPosition(group.position)}, controller_config_id = ${group.controllerConfigId || 900001}, `
    + `controller_gadget_id = ${group.controllerGadgetId || 70350081}${group.sceneryVariant ? ', scenery_variant = true' : ''} }\n`
    + 'require("QuestReconstruction/Story")\n';
  fs.writeFileSync(file, prefix + definition);
  changed.push(file);

  // These preserved spawn groups already belong to native blocks. Registering them
  // again would give one group two block owners and break load/unload lifetimes.
  if (!group.preserveExistingSpawnData) {
    const groups = byScene.get(group.sceneId) || [];
    groups.push(group);
    byScene.set(group.sceneId, groups);
  }
}

for (const [scene, groups] of byScene) {
  const block = 989000000 + scene;
  const directory = path.join(root, String(scene));
  const file = path.join(directory, `scene${scene}_block${block}.lua`);
  const lines = [
    '-- AstaPS custom reconstruction block. Original blocks remain intact.',
    'groups = {'
  ];
  for (const group of groups)
    lines.push(`  { id = ${group.groupId}, refresh_id = 0, pos = ${luaPosition(group.position)}, dynamic_load = true },`);
  lines.push('}', '');
  fs.writeFileSync(file, lines.join('\n'));
  changed.push(file);

  const index = path.join(directory, `scene${scene}.lua`);
  if (!fs.existsSync(index)) throw Error(`Original scene config missing: ${scene}`);
  let content = fs.readFileSync(index, 'utf8');
  if (content.includes(blockMarker)) {
    const ownSection = /-- ASTAPS_STORY_RECONSTRUCTION_BLOCK\r?\n-- Additional[^\n]*\r?\nblocks\[#blocks \+ 1\] = [^\n]*\r?\nblock_rects\[#block_rects \+ 1\] = [^\n]*\r?\n(?:-- END_ASTAPS_STORY_RECONSTRUCTION_BLOCK\r?\n)?/;
    if (!ownSection.test(content)) throw Error(`Cannot replace authored scene section: ${scene}`);
    content = content.replace(ownSection, '');
  }
  const x0 = Math.min(...groups.map(g => g.position.x)) - 100;
  const x1 = Math.max(...groups.map(g => g.position.x)) + 100;
  const z0 = Math.min(...groups.map(g => g.position.z)) - 100;
  const z1 = Math.max(...groups.map(g => g.position.z)) + 100;
  content = content.trimEnd() + `\n\n${blockMarker}\n`
    + '-- Additional, explicitly authored block; native indices above are unchanged.\n'
    + `blocks[#blocks + 1] = ${block}\n`
    + `block_rects[#block_rects + 1] = { min = { x = ${x0}, z = ${z0} }, max = { x = ${x1}, z = ${z1} } }\n`
    + '-- END_ASTAPS_STORY_RECONSTRUCTION_BLOCK\n';
  fs.writeFileSync(index, content);
  changed.push(index);
}

fs.mkdirSync('local', { recursive: true });
fs.writeFileSync('local/quest-story-reconstructed-resource-paths.json', JSON.stringify(changed, null, 2));
console.log(JSON.stringify({ groups: catalog.groups.length, newBlocks: byScene.size, resourceFiles: changed.length }));
