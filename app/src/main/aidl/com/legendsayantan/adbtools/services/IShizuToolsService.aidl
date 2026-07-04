package com.legendsayantan.adbtools.services;

interface IShizuToolsService {
    void setAppOpMode(String pkgName, int uid, int opCode, int mode);
    // Future methods (e.g., PM grants, display tweaks) will be added here.
}
