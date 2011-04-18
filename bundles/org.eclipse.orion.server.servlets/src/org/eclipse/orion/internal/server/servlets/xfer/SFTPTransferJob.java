/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.servlets.xfer;

import com.jcraft.jsch.*;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import java.io.File;
import java.io.IOException;
import java.util.Vector;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.orion.internal.server.servlets.Activator;
import org.eclipse.orion.server.core.tasks.ITaskService;
import org.eclipse.orion.server.core.tasks.TaskInfo;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Common base class for import/export over SFTP
 */
public abstract class SFTPTransferJob extends Job {

	protected final String host;
	protected final File localRoot;
	protected final String passphrase;
	protected final int port;
	protected final IPath remoteRoot;
	protected TaskInfo task;
	private ITaskService taskService;
	private ServiceReference<ITaskService> taskServiceRef;
	protected final String user;

	public SFTPTransferJob(File localFile, String host, int port, IPath remotePath, String user, String passphrase) {
		super("Transfer over SFTP"); //$NON-NLS-1$
		this.localRoot = localFile;
		this.host = host;
		this.port = port;
		this.remoteRoot = remotePath;
		this.user = user;
		this.passphrase = passphrase;
		this.task = createTask();
	}

	private void cleanUp() {
		taskService = null;
		if (taskServiceRef != null) {
			Activator.getDefault().getContext().ungetService(taskServiceRef);
			taskServiceRef = null;
		}
	}

	protected TaskInfo createTask() {
		TaskInfo info = getTaskService().createTask();
		info.setMessage(NLS.bind("Connecting to {0}...", host));
		getTaskService().updateTask(info);
		return info;
	}

	protected void doTransferDirectory(ChannelSftp channel, IPath remotePath, File localFile) throws SftpException, IOException {
		@SuppressWarnings("unchecked")
		Vector<LsEntry> children = channel.ls(remotePath.toString());
		for (LsEntry child : children) {
			String childName = child.getFilename();
			//skip self and parent references
			if (".".equals(childName) || "..".equals(childName)) //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			if (child.getAttrs().isDir()) {
				doTransferDirectory(channel, remotePath.append(childName), new File(localFile, childName));
			} else {
				doTransferFile(channel, remotePath.append(childName), new File(localFile, childName));
			}
		}
	}

	/**
	 * Method overwritten by subclass to implement import/export
	 */
	abstract void doTransferFile(ChannelSftp channel, IPath remotePath, File localFile) throws IOException, SftpException;

	public TaskInfo getTask() {
		return task;
	}

	protected ITaskService getTaskService() {
		if (taskService == null) {
			BundleContext context = Activator.getDefault().getContext();
			if (taskServiceRef == null) {
				taskServiceRef = context.getServiceReference(ITaskService.class);
				if (taskServiceRef == null)
					throw new IllegalStateException("Task service not available"); //$NON-NLS-1$
			}
			taskService = context.getService(taskServiceRef);
			if (taskService == null)
				throw new IllegalStateException("Task service not available"); //$NON-NLS-1$
		}
		return taskService;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		try {
			JSch jsch = new JSch();
			IStatus result = null;
			try {
				Session session = jsch.getSession(user, host, port);
				session.setUserInfo(new SFTPUserInfo(passphrase, passphrase));
				session.connect();
				try {
					ChannelSftp channel = (ChannelSftp) session.openChannel("sftp"); //$NON-NLS-1$
					try {
						channel.connect();
						doTransferDirectory(channel, remoteRoot, localRoot);
					} finally {
						channel.disconnect();
					}
				} finally {
					session.disconnect();
				}
			} catch (Exception e) {
				String msg = NLS.bind("Transfer from {0} failed: {1}", host + remoteRoot, e.getMessage());
				result = new Status(IStatus.ERROR, Activator.PI_SERVER_SERVLETS, msg, e);
			}
			if (result == null) {
				//fill in message to client for successful result
				String msg = NLS.bind("Transfer complete: {0}", host + remoteRoot);
				result = new Status(IStatus.OK, Activator.PI_SERVER_SERVLETS, msg);
			}
			//update task for both good or bad result cases
			task.done(result);
			getTaskService().updateTask(task);
			return result;
		} finally {
			cleanUp();
		}
	}

	protected void setTaskMessage(String message) {
		task.setMessage(message);
		getTaskService().updateTask(task);
	}
}