/*!
* Copyright 2002 - 2015 Webdetails, a Pentaho company. All rights reserved.
*
* This software was developed by Webdetails and is provided under the terms
* of the Mozilla Public License, Version 2.0, or any later version. You may not use
* this file except in compliance with the license. If you need a copy of the license,
* please go to http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
*
* Software distributed under the Mozilla Public License is distributed on an "AS IS"
* basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. Please refer to
* the license for the specific language governing your rights and limitations.
*/

package pt.webdetails.cfr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pentaho.platform.api.engine.IParameterProvider;

import pt.webdetails.cfr.auth.FilePermissionEnum;
import pt.webdetails.cfr.auth.FilePermissionMetadata;
import pt.webdetails.cfr.file.CfrFile;
import pt.webdetails.cfr.file.FileStorer;
import pt.webdetails.cfr.file.IFile;
import pt.webdetails.cfr.file.MetadataReader;
import pt.webdetails.cfr.repository.IFileRepository;
import pt.webdetails.cpf.InterPluginCall;
import pt.webdetails.cpf.SimpleContentGenerator;
import pt.webdetails.cpf.WrapperUtils;
import pt.webdetails.cpf.annotations.AccessLevel;
import pt.webdetails.cpf.annotations.Exposed;
import pt.webdetails.cpf.persistence.PersistenceEngine;
import pt.webdetails.cpf.utils.CharsetHelper;
import pt.webdetails.cpf.utils.MimeTypes;
import pt.webdetails.cpf.utils.PluginUtils;
import pt.webdetails.cpf.VersionChecker;

public class CfrContentGenerator extends SimpleContentGenerator {

  private static final long serialVersionUID = 1L;

  private static Map<String, Method> exposedMethods = new HashMap<String, Method>();

  private CfrService service = new CfrService();

  private MetadataReader mr = new MetadataReader( service );

  private static final String UI_PATH = "cfr/presentation/";
  private static final String DEFAULT_STORE_ERROR_MESSAGE = "Something went wrong when trying to upload the file";
  private static final String DEFAULT_REMOVE_ERROR_MESSAGE = "Something went wrong when trying to remove the file";
  private static final String DEFAULT_CREATE_ERROR_MESSAGE = "Something went wrong when trying to create the folder";
  private static final String ROOT = ".";

  static {
    // to keep case-insensitive methods
    exposedMethods = getExposedMethods( CfrContentGenerator.class, true );
  }

  static String checkRelativePathSanity( String path ) {
    String result = path;

    if ( path != null ) {
      if ( result.startsWith( "./" ) ) {
        result = result.replaceFirst( "./", "" );
      }
      if ( result.startsWith( "." ) ) {
        result = result.replaceFirst( ".", "" );
      }
      if ( result.startsWith( "/" ) ) {
        result = result.replaceFirst( ".", "" );
      }

      if ( result.endsWith( "/" ) ) {
        result = result.substring( 0, result.length() - 1 );
      }
    }

    return result;
  }

  static String relativeFilePath( String baseDir, String file ) {
    String _baseDir = checkRelativePathSanity( baseDir );
    String _file = checkRelativePathSanity( file );
    String result = null;

    if ( _baseDir == null || _baseDir.length() == 0 ) {
      return _file;
    } else {
      if ( _baseDir.endsWith( "/" ) ) {
        result = new StringBuilder( _baseDir ).append( _file ).toString();
      } else {
        result = new StringBuilder( _baseDir ).append( '/' ).append( _file ).toString();
      }
    }

    return result;
  }

  @Override
  protected Method getMethod( String methodName ) throws NoSuchMethodException {
    Method method = exposedMethods.get( StringUtils.lowerCase( methodName ) );
    if ( method == null ) {
      throw new NoSuchMethodException();
    }
    return method;
  }

  @Override
  public String getPluginName() {
    return "cfr";
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void home( OutputStream out ) throws IOException {

    IParameterProvider requestParams = getRequestParameters();
    // IParameterProvider pathParams = getPathParameters();
    ServletRequest wrapper = getRequest();
    String root = wrapper.getScheme() + "://" + wrapper.getServerName() + ":" + wrapper.getServerPort();

    Map<String, Object> params = new HashMap<String, Object>();
    params.put( "solution", "system" );
    params.put( "path", "cfr/presentation/" );
    params.put( "file", "cfr.wcdf" );
    params.put( "absolute", "false" );
    params.put( "inferScheme", "false" );
    params.put( "root", root );

    // add request parameters
    PluginUtils.copyParametersFromProvider( params, WrapperUtils.wrapParamProvider( requestParams ) );

    if ( requestParams.hasParameter( "mode" )
        && requestParams.getStringParameter( "mode", "Render" ).equals( "edit" ) ) {

      // Send this to CDE

      redirectToCdeEditor( out, params );
      return;
    }

    InterPluginCall pluginCall = new InterPluginCall( InterPluginCall.CDE, "Render", params );
    pluginCall.setResponse( getResponse() );
    pluginCall.setRequest( getRequest() );
    pluginCall.setOutputStream( out );
    pluginCall.run();

  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void createFolder( OutputStream out ) throws Exception {
    String path = checkRelativePathSanity( getRequestParameters().getStringParameter( "path", "" ) );

    if ( path == null || StringUtils.isBlank( path ) ) {
      throw new Exception( "path is null or empty" );
    }

    String parentFolder = extractParentFolder( path );
    if ( !mr.isCurrentUserAllowed( FilePermissionEnum.WRITE, parentFolder ) ) {
      logger.error( "user has no write permission on folder '" + parentFolder + "'" );
      writeOut( out, buildResponseJson( false, DEFAULT_CREATE_ERROR_MESSAGE ) );
    }

    boolean createResult = service.getRepository().createFolder( path );

    writeOut( out, new JSONObject().put( "result", createResult ).toString() );
  }

  private String extractParentFolder( String path ) {
    if ( path.contains( "/" ) ) {
      return path.substring( 0, path.lastIndexOf( "/" ) );
    } else {
      return ROOT;
    }
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void remove( OutputStream out ) throws Exception {
    String fullFileName = checkRelativePathSanity( getRequestParameters().getStringParameter( "fileName", null ) );

    if ( fullFileName == null || StringUtils.isBlank( fullFileName ) ) {
      throw new Exception( "fileName is null or empty" );
    }

    if ( !mr.isCurrentUserAllowed( FilePermissionEnum.DELETE, fullFileName ) ) {
      logger.error( "user has no delete permission on file '" + fullFileName + "'" );
      writeOut( out, buildResponseJson( false, DEFAULT_REMOVE_ERROR_MESSAGE ) );
    }

    boolean removeResult = service.getRepository().deleteFile( fullFileName );
    boolean result = false;
    if ( removeResult ) {
      FileStorer.deletePermissions( fullFileName, null );
      result = FileStorer.removeFile( fullFileName, null );
    }

    writeOut( out, new JSONObject().put( "result", result ).toString() );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void store( OutputStream out ) throws Exception {
    FileItemFactory factory = new DiskFileItemFactory();
    ServletFileUpload upload = new ServletFileUpload( factory );
    List /* FileItem */items = upload.parseRequest( getRequest() );

    writeOut( out, processStore( items ) );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.HTML )
  public void storeIE( OutputStream out ) throws Exception {
    FileItemFactory factory = new DiskFileItemFactory();
    ServletFileUpload upload = new ServletFileUpload( factory );
    List /* FileItem */items = upload.parseRequest( getRequest() );

    writeOut( out, "<textarea>" + processStore( items ) + "</textarea>" );
  }

  protected String processStore( List items ) throws JSONException {
    String fileName = null, savePath = null;
    byte[] contents = null;
    for ( int i = 0; i < items.size(); i++ ) {
      FileItem fi = (FileItem) items.get( i );

      if ( "path".equals( fi.getFieldName() ) ) {
        savePath = checkRelativePathSanity( fi.getString() );
      }
      if ( "file".equals( fi.getFieldName() ) ) {
        contents = fi.get();
        fileName = checkRelativePathSanity( fi.getName() );
      }
    }

    if ( fileName == null ) {
      logger.error( "parameter fileName must not be null" );
      return buildResponseJson( false, DEFAULT_STORE_ERROR_MESSAGE );
    }
    if ( savePath == null ) {
      logger.error( "parameter path must not be null" );
      return buildResponseJson( false, DEFAULT_STORE_ERROR_MESSAGE );
    }
    if ( contents == null ) {
      logger.error( "File content must not be null" );
      return buildResponseJson( false, DEFAULT_STORE_ERROR_MESSAGE );
    }
    if ( !mr.isCurrentUserAllowed( FilePermissionEnum.WRITE, StringUtils.isEmpty( savePath ) ? ROOT : savePath ) ) {
      logger.error( "user has no write permission on path '" + savePath + "'" );
      return buildResponseJson( false, DEFAULT_STORE_ERROR_MESSAGE );
    }

    FileStorer fileStorer = new FileStorer( service.getRepository() );
    String fullPath = FilenameUtils.normalize( savePath + "/" + fileName );
    if ( service.getRepository().fileExists( checkRelativePathSanity( fullPath ) ) ) {
      return buildResponseJson( false, "File " + fileName + " already exists!" );
    }
    return fileStorer.storeFile( fileName, savePath, contents, service.getCurrentUserName() ).toString();
  }

  protected String buildResponseJson( boolean status, String message ) throws JSONException {
    JSONObject result = new JSONObject();
    result.put( "result", status );
    result.put( "message", message );
    return result.toString();
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void listFiles( OutputStream out ) throws IOException {
    String baseDir = URLDecoder.decode( getRequestParameters().getStringParameter( "dir", "" ), "ISO-8859-1" );
    IFile[] files = service.getRepository().listFiles( baseDir );
    List<IFile> allowedFiles = new ArrayList<IFile>( files.length );
    String extensions = getRequestParameters().getStringParameter( "fileExtensions", "" );

    // checks permissions
    /*
     * remarks: ideally the repository must list only the files that the current user is allowed to access?
     */
    for ( IFile file : files ) {
      String relativePath = relativeFilePath( baseDir, file.getName() );
      if ( mr.isCurrentUserAllowed( FilePermissionEnum.READ, relativePath ) ) {
        allowedFiles.add( file );
      }
    }

    String[] exts = null;
    if ( !StringUtils.isBlank( extensions ) ) {
      exts = extensions.split( " " );
    }

    IFile[] allowedFilesArray = new IFile[ allowedFiles.size() ];
    writeOut( out, toJQueryFileTree( baseDir, allowedFiles.toArray( allowedFilesArray ), exts ) );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void getFile( OutputStream out ) throws IOException, JSONException, Exception {
    String fullFileName = checkRelativePathSanity( getRequestParameters().getStringParameter( "fileName", null ) );

    if ( fullFileName == null ) {
      logger.error( "request query parameter fileName must not be null" );
      throw new Exception( "request query parameter fileName must not be null" );
    }

    if ( mr.isCurrentUserAllowed( FilePermissionEnum.READ, fullFileName ) ) {
      CfrFile file = service.getRepository().getFile( fullFileName );

      setResponseHeaders( getMimeType( file.getFileName() ), -1,
          URLEncoder.encode( file.getFileName(), CharsetHelper.getEncoding() ) );
      ByteArrayInputStream bais = new ByteArrayInputStream( file.getContent() );
      IOUtils.copy( bais, out );
      IOUtils.closeQuietly( bais );
    } else {
      getResponse().sendError( HttpServletResponse.SC_UNAUTHORIZED, "you don't have permissions to access the file" );
    }
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void viewFile( OutputStream out ) throws IOException, JSONException, Exception {
    String fullFileName = checkRelativePathSanity( getRequestParameters().getStringParameter( "fileName", null ) );

    if ( fullFileName == null ) {
      logger.error( "request query parameter fileName must not be null" );
      throw new Exception( "request query parameter fileName must not be null" );
    }

    if ( mr.isCurrentUserAllowed( FilePermissionEnum.READ, fullFileName ) ) {
      CfrFile file = service.getRepository().getFile( fullFileName );

      setResponseHeaders( getMimeType( file.getFileName() ), -1, null );
      ByteArrayInputStream bais = new ByteArrayInputStream( file.getContent() );
      IOUtils.copy( bais, out );
      IOUtils.closeQuietly( bais );
    } else {
      getResponse().sendError( HttpServletResponse.SC_UNAUTHORIZED, "you don't have permissions to access the file" );
    }
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void listFilesJSON( OutputStream out ) throws IOException, JSONException {
    String baseDir = checkRelativePathSanity( getRequestParameters().getStringParameter( "dir", "" ) );
    IFile[] files = service.getRepository().listFiles( baseDir );
    JSONArray arr = new JSONArray();
    if ( files != null ) {
      for ( IFile file : files ) {
        if ( mr.isCurrentUserAllowed( FilePermissionEnum.READ, relativeFilePath( baseDir, file.getName() ) ) ) {
          JSONObject obj = new JSONObject();
          obj.put( "fileName", file.getName() );
          obj.put( "isDirectory", file.isDirectory() );
          obj.put( "path", baseDir );
          arr.put( obj );
        }
      }
    }

    writeOut( out, arr.toString( 2 ) );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void listUploads( OutputStream out ) throws IOException, JSONException {
    String path = checkRelativePathSanity( getRequestParameters().getStringParameter( "fileName", "" ) );
    writeOut( out, mr.listFiles( path, getRequestParameters().getStringParameter( "user", "" ), getRequestParameters()
        .getStringParameter( "startDate", "" ), getRequestParameters().getStringParameter( "endDate", "" ) ) );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void listUploadsFlat( OutputStream out ) throws IOException, JSONException {
    String path = checkRelativePathSanity( getRequestParameters().getStringParameter( "fileName", "" ) );
    writeOut( out, mr.listFilesFlat( path, getRequestParameters().getStringParameter( "user", "" ),
        getRequestParameters().getStringParameter( "startDate", "" ),
        getRequestParameters().getStringParameter( "endDate", "" ) ).toString( 2 ) );
  }

  private static String toJQueryFileTree( String baseDir, IFile[] files, String[] extensions ) {
    StringBuilder out = new StringBuilder();
    out.append( "<ul class=\"jqueryFileTree\" style=\"display: none;\">" );

    for ( IFile file : files ) {
      if ( file.isDirectory() ) {
        out.append( "<li class=\"directory collapsed\"><a href=\"#\" rel=\"" + baseDir + file.getName() + "/\">"
            + file.getName() + "</a></li>" );
      }
    }

    for ( IFile file : files ) {
      if ( !file.isDirectory() ) {
        int dotIndex = file.getName().lastIndexOf( '.' );
        String ext = dotIndex > 0 ? file.getName().substring( dotIndex + 1 ) : "";
        boolean accepted = ext.equals( "" );
        if ( !ext.equals( "" ) ) {
          if ( extensions == null || extensions.length == 0 ) {
            accepted = true;
          } else {
            for ( String acceptedExtension : extensions ) {
              if ( ext.equals( acceptedExtension ) ) {
                accepted = true;
                break;
              }
            }
          }
        }
        if ( accepted ) {
          out.append( "<li class=\"file ext_" + ext + "\"><a href=\"#\" rel=\"" + baseDir + file.getName() + "\">"
              + file.getName() + "</a></li>" );
        }
      }
    }
    out.append( "</ul>" );
    return out.toString();
  }

  private void redirectToCdeEditor( OutputStream out, Map<String, Object> params ) throws IOException {

    StringBuilder urlBuilder = new StringBuilder();
    urlBuilder.append( "../pentaho-cdf-dd/edit" );
    if ( params.size() > 0 ) {
      urlBuilder.append( "?" );
    }

    List<String> paramArray = new ArrayList<String>();
    for ( String key : params.keySet() ) {
      Object value = params.get( key );
      if ( value instanceof String ) {
        paramArray.add( key + "=" + URLEncoder.encode( (String) value, getEncoding() ) );
      }
    }

    urlBuilder.append( StringUtils.join( paramArray.iterator(), "&" ) );
    redirect( urlBuilder.toString() );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void setPermissions( OutputStream out ) throws JSONException, IOException {
    String path = getRequestParameters().getStringParameter( pathParameterPath, null );
    boolean isRoot = false;
    if ( ROOT.equals( path ) ) {
      isRoot = true;
    }
    path = checkRelativePathSanity( path );
    String[] userOrGroupId =
      getRequestParameters().getStringArrayParameter( pathParameterGroupOrUserId, new String[] {} );
    String[] _permissions =
      getRequestParameters().getStringArrayParameter( pathParameterPermission,
        new String[] { FilePermissionEnum.READ.getId() } );
    boolean recursive = Boolean.parseBoolean(
        getRequestParameters().getStringParameter( pathParameterRecursive, "false" ) );
    boolean errorSetting = false;

    JSONObject result = new JSONObject();
    if ( path != null && userOrGroupId.length > 0 && _permissions.length > 0 ) {
      List<String> files = new ArrayList<String>();
      if ( recursive ) {
        files = getFileNameTree( path );
      } else {
        files.add( path );
      }
      if ( isRoot ) {
        files.add( ROOT );
      }
      // build valid permissions set
      Set<FilePermissionEnum> validPermissions = new TreeSet<FilePermissionEnum>();
      for ( String permission : _permissions ) {
        FilePermissionEnum perm = FilePermissionEnum.resolve( permission );
        if ( perm != null ) {
          validPermissions.add( perm );
        }
      }
      JSONArray permissionAddResultArray = new JSONArray();

      for ( String file : files ) {
        CfrFile f = getRepository().getFile( file );
        if ( getRepository().getFile( path ).isDirectory() && f.isFile() ) {
          continue;
        }
        for ( String id : userOrGroupId ) {
          boolean storeResult =
              FileStorer.storeFilePermissions( new FilePermissionMetadata( file, id, validPermissions ) );
          if ( storeResult ) {
            permissionAddResultArray.put( new JSONObject()
                .put( "status", String.format( "Added permission for path %s and user/role %s", path, id ) ) );
          } else {
            if ( this.service.isCurrentUserAdmin() ) {
              permissionAddResultArray.put( new JSONObject()
                  .put( "status", String.format( "Failed to add permission for path %s and user/role %s", path, id ) ) );
            } else {
              errorSetting = true;
            }
          }
        }
      }

      result.put( "status", "Operation finished. Check statusArray for details." );
      if ( errorSetting ) {
        permissionAddResultArray.put( new JSONObject().put( "status", "Some permissions could not be set" ) );
      }
      result.put( "statusArray", permissionAddResultArray );
    } else {
      result.put( "status", "Path or user group parameters not found" );
    }

    writeOut( out, result.toString( 2 ) );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void deletePermissions( OutputStream out ) throws JSONException, IOException {
    String path = getRequestParameters().getStringParameter( pathParameterPath, null );
    boolean isRoot = false;
    if ( ROOT.equals( path ) ) {
      isRoot = true;
    }
    path = checkRelativePathSanity( path );
    String[] userOrGroupId =
      getRequestParameters().getStringArrayParameter( pathParameterGroupOrUserId, new String[] {} );
    boolean recursive = Boolean.parseBoolean(
        getRequestParameters().getStringParameter( pathParameterRecursive, "false" ) );
    boolean errorDeleting = false;
    JSONObject result = new JSONObject();

    if ( path != null || ( userOrGroupId != null && userOrGroupId.length > 0 ) ) {
      List<String> files = new ArrayList<String>();
      if ( isRoot ) {
        files.add( ROOT );
      }
      if ( recursive ) {
        files = getFileNameTree( path );
      } else {
        files.add( path );
      }
      JSONArray permissionDeleteResultArray = new JSONArray();
      if ( userOrGroupId == null || userOrGroupId.length == 0 ) {
        for ( String f : files ) {
          if ( FileStorer.deletePermissions( f, null ) ) {
            permissionDeleteResultArray.put( new JSONObject().put( "status", "Permissions for " + f + " deleted" ) );
          } else {
            if ( this.service.isCurrentUserAdmin() ) {
              permissionDeleteResultArray
                .put( new JSONObject().put( "status", "Error deleting permissions for " + f ) );
            } else {
              errorDeleting = true;
            }
          }
        }
        result.put( "status", "Multiple permission deletion. Check Status array" );
        if ( errorDeleting ) {
          permissionDeleteResultArray.put( new JSONObject().put( "status", "Some permissions could not be removed" ) );
        }
        result.put( "statusArray", permissionDeleteResultArray );
      } else {
        for ( String id : userOrGroupId ) {
          for ( String f : files ) {
            JSONObject individualResult = new JSONObject();
            boolean deleteResult = FileStorer.deletePermissions( f, id );
            if ( deleteResult ) {
              individualResult.put( "status", String.format( "Permission for %s and path %s deleted.", id, f ) );
            } else {
              individualResult
                .put( "status", String.format( "Failed to delete permission for %s and path %s.", id, f ) );
            }

            permissionDeleteResultArray.put( individualResult );
          }
        }
        result.put( "status", "Multiple permission deletion. Check Status array" );
        result.put( "statusArray", permissionDeleteResultArray );
      }
    } else {
      result.put( "status", "Required arguments user/role and path not found" );
    }
    writeOut( out, result.toString( 2 ) );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeType.JSON )
  public void getPermissions( OutputStream out ) throws IOException, JSONException {
    String path = checkRelativePathSanity( getRequestParameters().getStringParameter( pathParameterPath, null ) );
    String id = getRequestParameters().getStringParameter( pathParameterGroupOrUserId, null );
    if ( path != null || id != null ) {
      JSONArray permissions = mr.getPermissions( path, id, FilePermissionMetadata.DEFAULT_PERMISSIONS );
      writeOut( out, permissions.toString( 0 ) );
    }
  }

  @Exposed( accessLevel = AccessLevel.ADMIN )
  public void resetRepository( OutputStream out ) {
    PersistenceEngine.getInstance().dropClass( FileStorer.FILE_METADATA_STORE_CLASS );
    PersistenceEngine.getInstance().initializeClass( FileStorer.FILE_METADATA_STORE_CLASS );
    PersistenceEngine.getInstance().dropClass( FileStorer.FILE_PERMISSIONS_METADATA_STORE_CLASS );
    PersistenceEngine.getInstance().initializeClass( FileStorer.FILE_PERMISSIONS_METADATA_STORE_CLASS );

    IFileRepository repo = new CfrService().getRepository();
    for ( IFile file : repo.listFiles( "" ) ) {
      repo.deleteFile( file.getFullPath() );
    }

  }

  private static final String pathParameterGroupOrUserId = "id";

  private static final String pathParameterPath = "path";

  private static final String pathParameterPermission = "permission";

  private static final String pathParameterRecursive = "recursive";

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void checkVersion( OutputStream out ) throws IOException, JSONException {
    writeOut( out, getVersionChecker().checkVersion() );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC )
  public void getVersion( OutputStream out ) throws IOException, JSONException {
    writeOut( out, getVersionChecker().getVersion() );
  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeTypes.JSON )
  public void about( OutputStream out ) throws IOException, JSONException {
    renderInCde( out, getRenderRequestParameters( "cfrAbout.wcdf" ) );

  }

  @Exposed( accessLevel = AccessLevel.PUBLIC, outputType = MimeTypes.JSON )
  public void browser( OutputStream out ) throws IOException, JSONException {
    renderInCde( out, getRenderRequestParameters( "cfrBrowser.wcdf" ) );
  }

  public VersionChecker getVersionChecker() {

    return new VersionChecker( new CfrPluginSettings() ) {

      @Override
      protected String getVersionCheckUrl( VersionChecker.Branch branch ) {
        switch ( branch ) {
          case TRUNK:
            return "http://ci.pentaho.com/job/pentaho-cfr-pentaho/lastSuccessfulBuild/artifact/cfr-pentaho/dist"
              + "/marketplace.xml";
          //          case STABLE:
          //            return "http://ci.analytical-labs.com/job/Webdetails-CFR-Release/"
          //                + "lastSuccessfulBuild/artifact/dist/marketplace.xml";
          default:
            return null;
        }

      }

    };
  }

  private void renderInCde( OutputStream out, Map<String, Object> params ) throws IOException {
    InterPluginCall pluginCall = new InterPluginCall( InterPluginCall.CDE, "Render", params );
    pluginCall.setResponse( getResponse() );
    pluginCall.setRequest( getRequest() );
    pluginCall.setOutputStream( out );
    pluginCall.run();
  }

  private Map<String, Object> getRenderRequestParameters( String dashboardName ) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put( "solution", "system" );
    params.put( "path", UI_PATH );
    params.put( "file", dashboardName );
    params.put( "bypassCache", "true" );
    params.put( "absolute", "false" );
    params.put( "inferScheme", "false" );
    params.put( "root", getRoot() );

    // add request parameters
    ServletRequest request = getRequest();
    @SuppressWarnings( "unchecked" )
    // should always be String
      Enumeration<String> originalParams = request.getParameterNames();
    // Iterate and put the values there
    while ( originalParams.hasMoreElements() ) {
      String originalParam = originalParams.nextElement();
      params.put( originalParam, request.getParameter( originalParam ) );
    }

    return params;
  }

  private String getRoot() {

    ServletRequest wrapper = getRequest();
    String root = wrapper.getScheme() + "://" + wrapper.getServerName() + ":" + wrapper.getServerPort();

    return root;
  }

  protected List<String> getFileNameTree( String path ) {
    List<String> files = new ArrayList<String>();
    if ( !StringUtils.isEmpty( path ) ) {
      files.add( path );
    }

    files.addAll( buildFileNameTree( path, getFileNames( getRepository().listFiles( path ) ) ) );
    List<String> treatedFileNames = new ArrayList<String>();
    for ( String file : files ) {
      if ( file.startsWith( "/" ) ) {
        treatedFileNames.add( file.replaceFirst( "/", "" ) );
      } else {
        treatedFileNames.add( file );
      }
    }
    return treatedFileNames;
  }

  protected IFileRepository getRepository() {
    return service.getRepository();
  }

  private List<String> getFileNames( IFile[] files ) {
    List<String> names = new ArrayList<String>();
    for ( IFile file : files ) {
      names.add( file.getName() );
    }
    return names;
  }

  private List<String> buildFileNameTree( String basePath, List<String> children ) {
    List<String> result = new ArrayList<String>();
    for ( String child : children ) {
      String newEntry = basePath + "/" + child;
      result.add( newEntry );
      result.addAll( buildFileNameTree( newEntry, getFileNames( getRepository().listFiles( newEntry ) ) ) );
    }
    return result;
  }

}
