package com.pishi.hotfix;

import java.util.List;

/**
 * Created by c_kunwu on 16/5/12.
 * an interface describe patch.jar info
 */
public interface PatchesInfo {
    List<PatchedClassInfo> getPatchedClassesInfo();
}
