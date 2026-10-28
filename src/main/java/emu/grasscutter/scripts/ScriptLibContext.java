package emu.grasscutter.scripts;

import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

/** Keep Java method receivers intact while exposing the native per-call context fields. */
final class ScriptLibContext extends LuaUserdata {
    private final ScriptLib lib;
    private final LuaValue methods;

    ScriptLibContext(ScriptLib lib) {
        super(lib);
        this.lib = lib;
        this.methods = CoerceJavaToLua.coerce(lib);
    }

    @Override
    public LuaValue get(LuaValue key) {
        return switch (key.tojstring()) {
            case "uid" -> LuaValue.valueOf(lib.contextUid());
            case "owner_uid" -> LuaValue.valueOf(lib.GetSceneOwnerUid());
            case "source_entity_id" -> LuaValue.valueOf(lib.contextSourceEntityId());
            case "target_entity_id" -> LuaValue.valueOf(lib.contextTargetEntityId());
            default -> methods.get(key);
        };
    }
}
