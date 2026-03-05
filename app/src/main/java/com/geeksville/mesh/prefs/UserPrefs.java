package com.geeksville.mesh.prefs;

public class UserPrefs {

    public static class Hunting {
        public static final String SHARED_HUNT_PREFS = "hunt_prefs"; //nome shared pref

        // ----> params
        public static final String HUNT_MODE = "hunting_mode";
        public static final String BACKGROUND_HUNT = "background_hunt";
        public static final String HUNT_DOMAIN = "hunt_domain";
        public static final String HUNT_TOKEN = "hunt_token";
        public static final String BACKGROUND_HUNT_MODE = "background_hunt_mode";
        public static final String BACKGROUND_MODE_FAST = "FAST";
        public static final String BACKGROUND_MODE_MEDIUM = "MEDIUM";
        public static final String BACKGROUND_MODE_SLOW = "SLOW";
        public static final String BACKGROUND_MODE_SUPER_SLOW = "SUPER_SLOW";
        // <---- end params
    }

    public static class PlannedMessage {

        // ----> nome shared prefs che immagazzina lo stato del servizio di planning
        public static final String SHARED_PLANMSG_PREFS_STATUS = "planmsg_status";
        public static final String SHARED_PLANMSG_PREFS_SETTINGS = "planmsg_settings";
        // ----> params
        public static final String PLANMSG_SERVICE_ACTIVE = "status"; //param
        public static final String PLANMSG_MIGRATION_DONE = "migration_done";
        public static final String PLANMSG_LAST_ALARM_SCHEDULED_AT_UTC_MS = "last_alarm_scheduled_at_utc_ms";
        public static final String PLANMSG_LAST_ALARM_FIRED_AT_UTC_MS = "last_alarm_fired_at_utc_ms";
        public static final String PLANMSG_LAST_RUN_AT_UTC_MS = "last_run_at_utc_ms";
        public static final String PLANMSG_LAST_CLAIMED_COUNT = "last_claimed_count";
        public static final String PLANMSG_LAST_SENT_COUNT = "last_sent_count";
        public static final String PLANMSG_LAST_ERROR_REASON = "last_error_reason";
        // <---- end params


        // ----> nome shared prefs che immagazzina tutte le pianificaizoni
        public static final String SHARED_PLANNED_MSG_PREFS = "planmsg_prefs";
        //<---- no params
    }


}
