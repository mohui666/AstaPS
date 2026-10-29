package emu.grasscutter.scripts.data;

import java.util.List;
import lombok.*;

@ToString
@Setter
public class SceneMonster extends SceneObject {
    public int monster_id;
    /** Optional MGC-authored maximum HP, applied only to this spawned instance. */
    public float max_hp;
    /** Client display flag for an authored solo boss; does not add the elite combat ability. */
    public boolean show_boss_bar;
    public int pose_id;
    public int drop_id;
    public boolean disableWander;
    public int title_id;
    public int special_name_id;
    public String drop_tag;
    public List<Integer> affix;
    public boolean isElite;
}
