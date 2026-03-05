package com.geeksville.mesh.plannedmessages.data

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.prefs.UserPrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class PlannedMessageDatabaseModule {

    @Provides
    fun providePlannedMessageDao(database: MeshtasticDatabase): PlannedMessageDao {
        return database.plannedMessageDao()
    }

    @Provides
    @Singleton
    @PlannedMessageStatusPrefs
    fun providePlannedMessageStatusPrefs(app: Application): SharedPreferences {
        return app.getSharedPreferences(UserPrefs.PlannedMessage.SHARED_PLANMSG_PREFS_STATUS, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @LegacyPlannedMessagePrefs
    fun provideLegacyPlannedMessagePrefs(app: Application): SharedPreferences {
        return app.getSharedPreferences(UserPrefs.PlannedMessage.SHARED_PLANNED_MSG_PREFS, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @PlannedMessageSettingsPrefs
    fun providePlannedMessageSettingsPrefs(app: Application): SharedPreferences {
        return app.getSharedPreferences(UserPrefs.PlannedMessage.SHARED_PLANMSG_PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    @Provides
    fun provideAlarmManager(app: Application): AlarmManager {
        return app.getSystemService(AlarmManager::class.java)
    }
}
