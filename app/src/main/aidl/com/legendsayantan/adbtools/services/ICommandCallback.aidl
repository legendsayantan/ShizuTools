package com.legendsayantan.adbtools.services;

interface ICommandCallback {
    void onCommandResult(String output, boolean done);
    void onCommandError(String error);
}
