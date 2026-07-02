package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum JobActionType {
    @XmlEnumValue("COMMAND")
    COMMAND,

    @XmlEnumValue("SNIPPET_SCRIPT")
    SNIPPET_SCRIPT,

    @XmlEnumValue("AI_AGENT")
    AI_AGENT,

    @XmlEnumValue("AI_SWARM")
    AI_SWARM,

    @XmlEnumValue("SFTP_UPLOAD")
    SFTP_UPLOAD,

    @XmlEnumValue("SFTP_DOWNLOAD")
    SFTP_DOWNLOAD,

    @XmlEnumValue("SFTP_SYNC")
    SFTP_SYNC,

    @XmlEnumValue("SFTP_DELETE")
    SFTP_DELETE,

    @XmlEnumValue("SFTP_RENAME")
    SFTP_RENAME,

    @XmlEnumValue("SFTP_MKDIR")
    SFTP_MKDIR,

    @XmlEnumValue("SFTP_CHMOD")
    SFTP_CHMOD,

    @XmlEnumValue("SFTP_CHOWN")
    SFTP_CHOWN,

    @XmlEnumValue("SFTP_COPY_REMOTE")
    SFTP_COPY_REMOTE,

    @XmlEnumValue("SFTP_ARCHIVE")
    SFTP_ARCHIVE,

    @XmlEnumValue("RSYNC_SYNC")
    RSYNC_SYNC
}
