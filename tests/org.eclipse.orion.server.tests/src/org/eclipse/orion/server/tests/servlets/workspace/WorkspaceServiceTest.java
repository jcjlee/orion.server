/*******************************************************************************
 * Copyright (c) 2010, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.tests.servlets.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.meterware.httpunit.*;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.internal.server.servlets.workspace.ServletTestingSupport;
import org.eclipse.orion.internal.server.servlets.workspace.WorkspaceServlet;
import org.eclipse.orion.server.core.users.OrionScope;
import org.eclipse.orion.server.tests.servlets.files.FileSystemTest;
import org.json.*;
import org.junit.*;
import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;
import org.xml.sax.SAXException;

/**
 * Tests for {@link WorkspaceServlet}.
 */
public class WorkspaceServiceTest extends FileSystemTest {
	WebConversation webConversation;

	protected WebRequest getCreateProjectRequest(URI workspaceLocation, String projectName) {
		WebRequest request = new PostMethodWebRequest(workspaceLocation.toString());
		if (projectName != null)
			request.setHeaderField(ProtocolConstants.HEADER_SLUG, projectName);
		request.setHeaderField(ProtocolConstants.HEADER_ORION_VERSION, "1");
		setAuthentication(request);
		return request;
	}

	protected WebRequest getCopyMoveProjectRequest(URI workspaceLocation, String projectName, String sourceLocation, boolean isMove) throws UnsupportedEncodingException {
		JSONObject requestObject = new JSONObject();
		try {
			requestObject.put("Location", sourceLocation);
		} catch (JSONException e) {
			//should never happen
			Assert.fail("Invalid source location: " + sourceLocation);
		}
		InputStream source = getJsonAsStream(requestObject.toString());
		WebRequest request = new PostMethodWebRequest(workspaceLocation.toString(), source, "application/json");
		if (projectName != null)
			request.setHeaderField(ProtocolConstants.HEADER_SLUG, projectName);
		request.setHeaderField(ProtocolConstants.HEADER_ORION_VERSION, "1");
		request.setHeaderField(ProtocolConstants.HEADER_CREATE_OPTIONS, isMove ? "move" : "copy");
		setAuthentication(request);
		return request;
	}

	@BeforeClass
	public static void setupWorkspace() {
		initializeWorkspaceLocation();
	}

	WebResponse createWorkspace(String workspaceName) throws IOException, SAXException {
		WebRequest request = getCreateWorkspaceRequest(workspaceName);
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		return response;

	}

	@Before
	public void setUp() throws CoreException, BackingStoreException {
		clearWorkspace();
		OrionScope prefs = new OrionScope();
		prefs.getNode("Users").removeNode();
		prefs.getNode("Workspaces").removeNode();
		prefs.getNode("Projects").removeNode();
		webConversation = new WebConversation();
		webConversation.setExceptionsThrownOnErrorStatus(false);
		setUpAuthorization();
	}

	@Test
	public void testCreateProject() throws IOException, SAXException, JSONException, URISyntaxException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testCreateProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField("Location"));

		//create a project
		String projectName = "My Project";
		WebRequest request = getCreateProjectRequest(workspaceLocation, projectName);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());
		String locationHeader = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);
		assertNotNull(locationHeader);

		JSONObject project = new JSONObject(response.getText());
		assertEquals(projectName, project.getString(ProtocolConstants.KEY_NAME));
		String projectId = project.optString(ProtocolConstants.KEY_ID, null);
		assertNotNull(projectId);

		//ensure project location  workspace location + project id
		URI projectLocation = new URI(locationHeader);
		URI relative = workspaceLocation.relativize(projectLocation);
		assertEquals(projectId, relative.getPath());

		//ensure project appears in the workspace metadata
		request = new GetMethodWebRequest(workspaceLocation.toString());
		setAuthentication(request);
		response = webConversation.getResponse(request);
		JSONObject workspace = new JSONObject(response.getText());
		assertNotNull(workspace);
		JSONArray projects = workspace.getJSONArray("Projects");
		assertEquals(1, projects.length());
		JSONObject createdProject = projects.getJSONObject(0);
		assertEquals(projectId, createdProject.get(ProtocolConstants.KEY_ID));
		assertNotNull(createdProject.optString(ProtocolConstants.KEY_LOCATION, null));

		//check for children element to conform to structure of file API
		JSONArray children = workspace.optJSONArray("Children");
		assertNotNull(children);
		assertEquals(1, children.length());
		JSONObject child = children.getJSONObject(0);
		assertEquals(projectName, child.optString(ProtocolConstants.KEY_NAME));
		assertEquals("true", child.optString("Directory"));
		String contentLocation = child.optString(ProtocolConstants.KEY_LOCATION);
		assertNotNull(contentLocation);
	}

	@Test
	public void testMoveBadRequest() throws IOException, SAXException, URISyntaxException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testMoveProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField(ProtocolConstants.HEADER_LOCATION));

		//request a bogus move
		String projectName = "My Project";
		WebRequest request = getCopyMoveProjectRequest(workspaceLocation, projectName, "badsource", true);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, response.getResponseCode());

	}

	@Test
	public void testMoveProject() throws IOException, SAXException, URISyntaxException, JSONException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testMoveProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField(ProtocolConstants.HEADER_LOCATION));

		//create a project
		String projectName = "Source Project";
		WebRequest request = getCreateProjectRequest(workspaceLocation, projectName);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());
		String sourceLocation = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);

		// move the project
		String destinationName = "Destination Project";
		request = getCopyMoveProjectRequest(workspaceLocation, destinationName, sourceLocation, true);
		response = webConversation.getResponse(request);
		//since project already existed, we should get OK rather than CREATED
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		String destinationLocation = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);

		//assert the move (rename) took effect
		assertEquals(sourceLocation, destinationLocation);
		JSONObject resultObject = new JSONObject(response.getText());
		assertEquals(destinationName, resultObject.getString("Name"));
	}

	@Test
	public void testCopyProject() throws IOException, SAXException, URISyntaxException, JSONException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testMoveProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField(ProtocolConstants.HEADER_LOCATION));

		//create a project
		String projectName = "Source Project";
		WebRequest request = getCreateProjectRequest(workspaceLocation, projectName);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());
		String sourceLocation = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);
		JSONObject responseObject = new JSONObject(response.getText());
		String contentLocation = responseObject.optString("ContentLocation");
		assertNotNull(contentLocation);

		//add a file in the project
		request = getPostFilesRequest(contentLocation, "{}", "file.txt");
		response = webConversation.getResource(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());

		// copy the project
		//		String destinationName = "Destination Project";
		//		request = getCopyMoveProjectRequest(workspaceLocation, destinationName, sourceLocation, false);
		//		response = webConversation.getResponse(request);
		//		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());
		//		String destinationLocation = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);

		//assert the copy took effect
		//		assertFalse(sourceLocation.equals(destinationLocation));
		//		JSONObject resultObject = new JSONObject(response.getText());
		//		assertEquals(destinationName, resultObject.getString("Name"));
	}

	@Test
	public void testCreateProjectBadName() throws IOException, SAXException, URISyntaxException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testCreateProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField(ProtocolConstants.HEADER_LOCATION));

		//check a variety of bad project names
		for (String badName : Arrays.asList("", " ", "/")) {
			//create a project
			WebRequest request = getCreateProjectRequest(workspaceLocation, badName);
			response = webConversation.getResponse(request);
			assertEquals("Shouldn't allow name: " + badName, HttpURLConnection.HTTP_BAD_REQUEST, response.getResponseCode());
		}
	}

	/**
	 * Tests creating a project that is stored at a non-default location on the server.
	 */
	@Test
	public void testCreateProjectNonDefaultLocation() throws IOException, SAXException, JSONException, URISyntaxException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testCreateProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField(ProtocolConstants.HEADER_LOCATION));

		String tmp = System.getProperty("java.io.tmpdir");
		File projectLocation = new File(new File(tmp), "Orion-testCreateProjectNonDefaultLocation");
		projectLocation.mkdir();

		//at first forbid all project locations
		ServletTestingSupport.allowedPrefixes = null;

		//create a project
		String projectName = "My Project";
		JSONObject body = new JSONObject();
		body.put(ProtocolConstants.KEY_CONTENT_LOCATION, projectLocation.toString());
		InputStream in = new StringBufferInputStream(body.toString());
		WebRequest request = new PostMethodWebRequest(workspaceLocation.toString(), in, "UTF-8");
		if (projectName != null)
			request.setHeaderField(ProtocolConstants.HEADER_SLUG, projectName);
		request.setHeaderField(ProtocolConstants.HEADER_ORION_VERSION, "1");
		setAuthentication(request);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_FORBIDDEN, response.getResponseCode());

		//now set the allowed prefixes and try again
		ServletTestingSupport.allowedPrefixes = projectLocation.toString();
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());
		JSONObject project = new JSONObject(response.getText());
		assertEquals(projectName, project.getString(ProtocolConstants.KEY_NAME));
		String projectId = project.optString(ProtocolConstants.KEY_ID, null);
		assertNotNull(projectId);
	}

	@Test
	public void testCreateWorkspace() throws IOException, SAXException, JSONException {
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testCreateWorkspace";
		WebResponse response = createWorkspace(workspaceName);

		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		String location = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);
		assertNotNull(location);
		assertTrue(location.startsWith(SERVER_LOCATION));
		assertEquals("application/json", response.getContentType());
		JSONObject responseObject = new JSONObject(response.getText());
		assertNotNull("No workspace information in response", responseObject);
		assertNotNull(responseObject.optString(ProtocolConstants.KEY_ID));
		assertEquals(workspaceName, responseObject.optString(ProtocolConstants.KEY_NAME));
	}

	@Test
	public void testGetWorkspaceMetadata() throws IOException, SAXException, JSONException, URISyntaxException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testCreateProject";
		WebResponse response = createWorkspace(workspaceName);
		String locationString = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);
		URI workspaceLocation = new URI(locationString);
		JSONObject workspace = new JSONObject(response.getText());
		String workspaceId = workspace.getString(ProtocolConstants.KEY_ID);

		//get workspace metadata and ensure it is correct
		WebRequest request = new GetMethodWebRequest(workspaceLocation.toString());
		setAuthentication(request);
		response = webConversation.getResponse(request);
		workspace = new JSONObject(response.getText());
		assertNotNull(workspace);
		assertEquals(locationString, workspace.optString(ProtocolConstants.KEY_LOCATION));
		assertEquals(workspaceName, workspace.optString(ProtocolConstants.KEY_NAME));
		assertEquals(workspaceId, workspace.optString(ProtocolConstants.KEY_ID));
	}

	@Test
	public void testCreateWorkspaceNullName() throws IOException, SAXException, JSONException {
		//request with null workspace name is not allowed
		WebRequest request = getCreateWorkspaceRequest(null);
		WebResponse response = webConversation.getResponse(request);

		assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, response.getResponseCode());
		assertEquals("application/json", response.getContentType());
		//expecting a status response
		JSONObject responseObject = new JSONObject(response.getText());
		assertNotNull("No error response", responseObject);
		assertEquals("Error", responseObject.optString("Severity"));
		assertNotNull(responseObject.optString("message"));
	}

	@Test
	public void testDeleteProject() throws IOException, SAXException, JSONException, URISyntaxException {
		//create workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testDeleteProject";
		WebResponse response = createWorkspace(workspaceName);
		URI workspaceLocation = new URI(response.getHeaderField(ProtocolConstants.HEADER_LOCATION));

		//create a project
		String projectName = "My Project";
		WebRequest request = getCreateProjectRequest(workspaceLocation, projectName);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, response.getResponseCode());
		String projectLocation = response.getHeaderField(ProtocolConstants.HEADER_LOCATION);

		//delete project
		JSONObject data = new JSONObject();
		data.put("Remove", "true");
		data.put("ProjectURL", projectLocation);
		request = new PostMethodWebRequest(workspaceLocation.toString(), new ByteArrayInputStream(data.toString().getBytes()), "UTF8");
		request.setHeaderField(ProtocolConstants.HEADER_ORION_VERSION, "1");
		setAuthentication(request);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		//deleting again should be safe (DELETE is idempotent)
		request = new PostMethodWebRequest(workspaceLocation.toString(), new ByteArrayInputStream(data.toString().getBytes()), "UTF8");
		request.setHeaderField(ProtocolConstants.HEADER_ORION_VERSION, "1");
		setAuthentication(request);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

	}

	@Test
	public void testGetWorkspaces() throws IOException, SAXException, JSONException {
		WebRequest request = new GetMethodWebRequest(SERVER_LOCATION + "/workspace");
		setAuthentication(request);
		WebResponse response = webConversation.getResponse(request);

		//before creating an workspaces we should get an empty list
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		assertEquals("application/json", response.getContentType());
		JSONObject responseObject = new JSONObject(response.getText());
		assertNotNull("No workspace information in response", responseObject);
		String userId = responseObject.optString(ProtocolConstants.KEY_ID, null);
		assertNotNull(userId);
		assertEquals("test", responseObject.optString("UserName"));
		JSONArray workspaces = responseObject.optJSONArray("Workspaces");
		assertNotNull(workspaces);
		assertEquals(0, workspaces.length());

		//now create a workspace
		String workspaceName = WorkspaceServiceTest.class.getName() + "#testGetWorkspaces";
		response = createWorkspace(workspaceName);
		responseObject = new JSONObject(response.getText());
		String workspaceId = responseObject.optString(ProtocolConstants.KEY_ID, null);
		assertNotNull(workspaceId);

		//get the workspace list again
		request = new GetMethodWebRequest(SERVER_LOCATION + "/workspace");
		setAuthentication(request);
		response = webConversation.getResponse(request);

		//assert that the workspace we created is found by a subsequent GET
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		assertEquals("application/json", response.getContentType());
		responseObject = new JSONObject(response.getText());
		assertNotNull("No workspace information in response", responseObject);
		assertEquals(userId, responseObject.optString(ProtocolConstants.KEY_ID));
		assertEquals("test", responseObject.optString("UserName"));
		workspaces = responseObject.optJSONArray("Workspaces");
		assertNotNull(workspaces);
		assertEquals(1, workspaces.length());
		JSONObject workspace = (JSONObject) workspaces.get(0);
		assertEquals(workspaceId, workspace.optString(ProtocolConstants.KEY_ID));
		assertNotNull(workspace.optString(ProtocolConstants.KEY_LOCATION, null));
	}
}
