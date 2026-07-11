package com.darkxvenom.airbeats.utils

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.api.client.http.FileContent
import java.io.FileOutputStream
import com.darkxvenom.airbeats.BuildConfig

class GoogleAuthManager(private val context: Context) {
    
    companion object {
        const val WEB_CLIENT_ID = "83152931540-5tmnaka8rvkp5hh8ucihhl9cksuo9lob.apps.googleusercontent.com"
        const val BACKUP_FILE_NAME = "airbeats_backup.backup"
    }

    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .requestScopes(
                Scope(DriveScopes.DRIVE_APPDATA),
                Scope("https://www.googleapis.com/auth/youtube")
            )
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun signOut(onComplete: () -> Unit) {
        getSignInClient().signOut().addOnCompleteListener {
            onComplete()
        }
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_APPDATA, "https://www.googleapis.com/auth/youtube")
        )
        credential.selectedAccount = account.account
        
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("AirBeats").build()
    }

    suspend fun uploadBackupToDrive(account: GoogleSignInAccount, backupFile: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val driveService = getDriveService(account)
            
            // Check if file already exists in AppData folder
            val fileList = driveService.files().list()
                .setSpaces("appDataFolder")
                .setFields("nextPageToken, files(id, name)")
                .setQ("name='$BACKUP_FILE_NAME'")
                .execute()

            val fileContent = FileContent("application/octet-stream", backupFile)
            
            if (fileList.files.isNotEmpty()) {
                // Update existing
                val existingFileId = fileList.files[0].id
                driveService.files().update(existingFileId, null, fileContent).execute()
                existingFileId
            } else {
                // Create new
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                val file = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id")
                    .execute()
                file.id
            }
        }
    }

    suspend fun downloadBackupFromDrive(account: GoogleSignInAccount, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val driveService = getDriveService(account)
            
            val fileList = driveService.files().list()
                .setSpaces("appDataFolder")
                .setFields("nextPageToken, files(id, name)")
                .setQ("name='$BACKUP_FILE_NAME'")
                .execute()

            if (fileList.files.isEmpty()) {
                throw Exception("No backup found on Google Drive")
            }
            
            val fileId = fileList.files[0].id
            FileOutputStream(destFile).use { outStream ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(outStream)
            }
            destFile
        }
    }
}
