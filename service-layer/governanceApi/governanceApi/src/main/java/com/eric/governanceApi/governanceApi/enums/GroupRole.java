package com.eric.governanceApi.governanceApi.enums;

public enum GroupRole {
    OWNER,   // manage members and devices within the group
    MEMBER,  // view and send commands to devices in the group
    VIEWER   // read-only access to devices in the group
}
