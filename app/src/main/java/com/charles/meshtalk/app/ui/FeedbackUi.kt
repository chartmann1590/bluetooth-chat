package com.charles.meshtalk.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.charles.meshtalk.app.BuildConfig
import com.charles.meshtalk.app.data.feedback.BugReport
import com.charles.meshtalk.app.data.feedback.BugReportRepo
import com.charles.meshtalk.app.data.feedback.CreateIssueRequest
import com.charles.meshtalk.app.data.feedback.DiagnosticsHelper
import com.charles.meshtalk.app.data.feedback.GithubClient
import com.charles.meshtalk.app.data.feedback.GithubComment
import com.charles.meshtalk.app.data.feedback.GithubIssue
import com.charles.meshtalk.app.data.feedback.GithubUser
import com.charles.meshtalk.app.data.feedback.ImageUploadHelper
import com.charles.meshtalk.app.data.feedback.PostCommentRequest
import com.charles.meshtalk.app.data.feedback.UploadAssetRequest
import com.charles.meshtalk.app.ui.theme.MeshBubbleIncoming
import com.charles.meshtalk.app.ui.theme.MeshOnSurfaceMuted
import com.charles.meshtalk.app.ui.theme.MeshSurface
import com.charles.meshtalk.app.ui.theme.SignalGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SupportFeedbackSection(
    repo: BugReportRepo,
    onDataChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reports by repo.bugReports.collectAsState(initial = emptyList())

    var showReportDialog by remember { mutableStateOf(false) }
    var selectedReport by remember { mutableStateOf<BugReport?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MeshSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Support & Feedback",
                style = MaterialTheme.typography.titleMedium,
                color = SignalGreen
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showReportDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = SignalGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Report a Problem")
            }

            if (reports.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Submitted Reports",
                    style = MaterialTheme.typography.labelMedium,
                    color = MeshOnSurfaceMuted
                )
                Spacer(modifier = Modifier.height(8.dp))

                reports.take(10).forEach { report ->
                    ReportRow(
                        report = report,
                        onClick = { selectedReport = report }
                    )
                }
            }
        }
    }

    if (showReportDialog) {
        ReportDialog(
            repo = repo,
            onDismiss = { showReportDialog = false; onDataChanged() },
            onSubmitted = { report ->
                showReportDialog = false
                scope.launch { repo.saveBugReport(report) }
                onDataChanged()
            }
        )
    }

    selectedReport?.let { report ->
        IssueDetailDialog(
            report = report,
            repo = repo,
            onDismiss = {
                selectedReport = null
                onDataChanged()
            }
        )
    }
}

@Composable
private fun ReportRow(report: BugReport, onClick: () -> Unit) {
    val isOpen = report.status.equals("open", ignoreCase = true)
    val statusColor = if (isOpen) SignalGreen else Color(0xFFCF6679)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MeshBubbleIncoming),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isOpen) Icons.Filled.CheckCircle else Icons.Filled.Close,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    report.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Row {
                    Text(
                        "#${report.number}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshOnSurfaceMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        report.createdAt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshOnSurfaceMuted
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    report.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MeshOnSurfaceMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDialog(
    repo: BugReportRepo,
    onDismiss: () -> Unit,
    onSubmitted: (BugReport) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var userName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attachmentUri = uri
    }

    val configured = GithubClient.isConfigured
    val canSubmit = title.isNotBlank() && description.isNotBlank() && configured && !isSubmitting

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text("Report a Problem", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF332B00)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Your report will be submitted to this app's GitHub issue tracker. Do not include passwords, private keys, medical information, financial information, or anything you do not want visible to the repository maintainers. If this repository is public, your report may be publicly visible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFF8E1)
                            )
                        }
                    }
                }

                if (!configured) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF3E1010)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "GitHub not configured. Add github.api.token, github.repo.owner, and github.repo.name to local.properties.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFCDD2)
                            )
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title / Subject *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSubmitting
                    )
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description *") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 6,
                        enabled = !isSubmitting
                    )
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeDiagnostics,
                            onCheckedChange = { includeDiagnostics = it },
                            enabled = !isSubmitting
                        )
                        Text(
                            "Include phone/app diagnostics",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Name (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSubmitting
                    )
                }

                item {
                    OutlinedTextField(
                        value = userEmail,
                        onValueChange = { userEmail = it },
                        label = { Text("Email (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        enabled = !isSubmitting
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            enabled = !isSubmitting
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (attachmentUri != null) "Change Screenshot" else "Attach Screenshot")
                        }

                        if (attachmentUri != null) {
                            TextButton(
                                onClick = { attachmentUri = null },
                                enabled = !isSubmitting
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }

                if (attachmentUri != null) {
                    item {
                        AsyncImage(
                            model = attachmentUri,
                            contentDescription = "Screenshot preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                errorMessage?.let { msg ->
                    item {
                        Text(
                            msg,
                            color = Color(0xFFCF6679),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                successMessage?.let { msg ->
                    item {
                        Text(
                            msg,
                            color = SignalGreen,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSubmitting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val created = submitBugReport(
                                context = context,
                                title = title,
                                description = description,
                                includeDiagnostics = includeDiagnostics,
                                userName = userName,
                                userEmail = userEmail,
                                attachmentUri = attachmentUri
                            )
                            onSubmitted(created)
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Submission failed"
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = SignalGreen)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isSubmitting) "Submitting..." else "Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailDialog(
    report: BugReport,
    repo: BugReportRepo,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var issue by remember { mutableStateOf<GithubIssue?>(null) }
    var comments by remember { mutableStateOf<List<GithubComment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var replyText by remember { mutableStateOf("") }
    var replyAttachmentUri by remember { mutableStateOf<Uri?>(null) }
    var isPostingReply by remember { mutableStateOf(false) }
    var replyError by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) replyAttachmentUri = uri
    }

    val fetchData: suspend () -> Unit = {
        try {
            isLoading = true
            loadError = null
            val issueResp = GithubClient.service.getIssue(
                GithubClient.owner, GithubClient.repo, report.number
            )
            if (issueResp.isSuccessful) {
                issue = issueResp.body()
                val fetchedIssue = issueResp.body()!!
                if (fetchedIssue.state != report.status) {
                    repo.saveBugReport(
                        report.copy(
                            status = fetchedIssue.state,
                            htmlUrl = fetchedIssue.htmlUrl
                        )
                    )
                }
            }
            val commentsResp = GithubClient.service.getComments(
                GithubClient.owner, GithubClient.repo, report.number
            )
            if (commentsResp.isSuccessful) {
                comments = commentsResp.body() ?: emptyList()
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to fetch issue"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchData() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Issue #${report.number}", style = MaterialTheme.typography.titleLarge)
                issue?.let { iss ->
                    Text(
                        iss.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshOnSurfaceMuted
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isLoading || isRefreshing) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SignalGreen)
                    }
                    return@Column
                }

                loadError?.let { err ->
                    Text(err, color = Color(0xFFCF6679), style = MaterialTheme.typography.bodySmall)
                    return@Column
                }

                issue?.let { iss ->
                    val isOpen = iss.state.equals("open", ignoreCase = true)
                    val statusColor = if (isOpen) SignalGreen else Color(0xFFCF6679)

                    Row {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(iss.state.uppercase(), color = statusColor, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(iss.createdAt, style = MaterialTheme.typography.bodySmall, color = MeshOnSurfaceMuted)
                    }

                    iss.body?.let { body ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(body, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Comments (${comments.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = SignalGreen
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    items(comments) { comment ->
                        CommentItem(comment)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    label = { Text("Write a reply...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    enabled = !isPostingReply
                )

                Spacer(modifier = Modifier.height(8.dp))

                replyError?.let { err ->
                    Text(err, color = Color(0xFFCF6679), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            enabled = !isPostingReply
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (replyAttachmentUri != null) "Change Image" else "Attach Image")
                        }
                        if (replyAttachmentUri != null) {
                            TextButton(onClick = { replyAttachmentUri = null }, enabled = !isPostingReply) {
                                Text("Remove")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (replyText.isBlank() && replyAttachmentUri == null) return@Button
                            isPostingReply = true
                            replyError = null
                            scope.launch {
                                try {
                                    postComment(
                                        context = context,
                                        issueNumber = report.number,
                                        text = replyText,
                                        attachmentUri = replyAttachmentUri
                                    )
                                    replyText = ""
                                    replyAttachmentUri = null
                                    fetchData()
                                } catch (e: Exception) {
                                    replyError = e.message ?: "Failed to post comment"
                                } finally {
                                    isPostingReply = false
                                }
                            }
                        },
                        enabled = (replyText.isNotBlank() || replyAttachmentUri != null) && !isPostingReply,
                        colors = ButtonDefaults.buttonColors(containerColor = SignalGreen)
                    ) {
                        if (isPostingReply) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reply")
                        }
                    }
                }

                if (replyAttachmentUri != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = replyAttachmentUri,
                        contentDescription = "Attachment preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    isRefreshing = true
                    fetchData()
                    isRefreshing = false
                }
            }) {
                Text("Refresh")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CommentItem(comment: GithubComment) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MeshBubbleIncoming),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.user.login,
                    style = MaterialTheme.typography.labelMedium,
                    color = SignalGreen,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    comment.createdAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshOnSurfaceMuted
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(comment.body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private suspend fun submitBugReport(
    context: android.content.Context,
    title: String,
    description: String,
    includeDiagnostics: Boolean,
    userName: String,
    userEmail: String,
    attachmentUri: Uri?
): BugReport = withContext(Dispatchers.IO) {
    var screenshotUrl: String? = null

    if (attachmentUri != null) {
        val base64 = ImageUploadHelper.uriToBase64(context, attachmentUri)
        val fileName = "feedback-assets/issue-${dateStamp()}-${shortUuid()}.png"
        val uploadReq = UploadAssetRequest(
            message = "Upload screenshot for feedback",
            content = base64
        )
        val uploadResp = GithubClient.service.uploadAsset(
            GithubClient.owner, GithubClient.repo, fileName, uploadReq
        )
        if (uploadResp.isSuccessful) {
            screenshotUrl = uploadResp.body()?.content?.downloadUrl
        }
    }

    val body = buildString {
        appendLine("## Description")
        appendLine()
        appendLine(description)
        appendLine()
        appendLine("## Contact Info")
        appendLine()
        appendLine("- Name: ${userName.ifBlank { "Not provided" }}")
        appendLine("- Email: ${userEmail.ifBlank { "Not provided" }}")
        appendLine()
        if (screenshotUrl != null) {
            appendLine("## Attachment")
            appendLine()
            appendLine("![Screenshot]($screenshotUrl)")
            appendLine()
        }
        if (includeDiagnostics) {
            appendLine(DiagnosticsHelper.collectDiagnostics(context))
        }
    }

    val request = CreateIssueRequest(
        title = "[Feedback] $title",
        body = body
    )
    val response = GithubClient.service.createIssue(GithubClient.owner, GithubClient.repo, request)
    if (!response.isSuccessful) {
        throw Exception("GitHub error: ${response.code()} ${response.message()} ${response.errorBody()?.string()}")
    }
    val issue = response.body()!!

    BugReport(
        number = issue.number,
        title = issue.title,
        status = issue.state,
        createdAt = issue.createdAt,
        htmlUrl = issue.htmlUrl
    )
}

private suspend fun postComment(
    context: android.content.Context,
    issueNumber: Int,
    text: String,
    attachmentUri: Uri?
) = withContext(Dispatchers.IO) {
    var screenshotUrl: String? = null

    if (attachmentUri != null) {
        val base64 = ImageUploadHelper.uriToBase64(context, attachmentUri)
        val fileName = "feedback-assets/comment-${dateStamp()}-${shortUuid()}.png"
        val uploadReq = UploadAssetRequest(
            message = "Upload screenshot for comment",
            content = base64
        )
        val uploadResp = GithubClient.service.uploadAsset(
            GithubClient.owner, GithubClient.repo, fileName, uploadReq
        )
        if (uploadResp.isSuccessful) {
            screenshotUrl = uploadResp.body()?.content?.downloadUrl
        } else {
            throw Exception("Failed to upload attachment: ${uploadResp.code()} ${uploadResp.message()}")
        }
    }

    val body = buildString {
        appendLine("## Reply")
        appendLine()
        appendLine(text)
        if (screenshotUrl != null) {
            appendLine()
            appendLine("## Attachment")
            appendLine()
            appendLine("![Screenshot]($screenshotUrl)")
        }
    }

    val response = GithubClient.service.postComment(
        GithubClient.owner, GithubClient.repo, issueNumber, PostCommentRequest(body)
    )
    if (!response.isSuccessful) {
        throw Exception("Failed to post comment: ${response.code()} ${response.message()}")
    }
}

private fun dateStamp(): String {
    return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

private fun shortUuid(): String {
    return UUID.randomUUID().toString().take(8)
}
