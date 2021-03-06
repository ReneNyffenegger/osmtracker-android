package me.guillaumin.android.osmtracker.gpx;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;

import me.guillaumin.android.osmtracker.OSMTracker;
import me.guillaumin.android.osmtracker.R;
import me.guillaumin.android.osmtracker.db.DataHelper;
import me.guillaumin.android.osmtracker.db.TrackContentProvider;
import me.guillaumin.android.osmtracker.db.TrackContentProvider.Schema;
import me.guillaumin.android.osmtracker.exception.ExportTrackException;
import me.guillaumin.android.osmtracker.util.FileSystemUtils;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Writes a GPX file.
 * 
 * @author Nicolas Guillaumin
 *
 */
public class ExportTrackTask  extends AsyncTask<Void, Integer, Boolean> {

	private static final String TAG = ExportTrackTask.class.getSimpleName();
	
	/**
	 * XML header.
	 */
	private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>";
	
	private static final String CDATA_START = "<![CDATA[";
	private static final String CDATA_END = "]]>";
	
	/**
	 * GPX opening tag
	 */
	private static final String TAG_GPX = "<gpx"
		+ " xmlns=\"http://www.topografix.com/GPX/1/1\""
		+ " version=\"1.1\""
		+ " creator=\"osmtracker-android\""		// TODO: Get name in resources ?
		+ " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
		+ " xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd \">";
	
	/**
	 * Date format for a point timestamp.
	 */
	private static SimpleDateFormat POINT_DATE_FORMATTER = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	static {
		POINT_DATE_FORMATTER.setTimeZone(TimeZone.getTimeZone("UTC"));
	}
	
	/**
	 * {@link Context} to get resources
	 */
	private Context context;
	
	/**
	 * Track ID to export
	 */
	private long trackId;
	
	/**
	 * Dialog to display while exporting
	 */
	private ProgressDialog dialog;

	/**
	 * Message in case of an error
	 */
	private String errorMsg = null;
	
	public ExportTrackTask(Context context, long trackId) {
		this.context = context;
		this.trackId = trackId;
	}

	
	@Override
	protected void onPreExecute() {
		// Display dialog
		dialog = new ProgressDialog(context);
		dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		dialog.setIndeterminate(true);
		dialog.setTitle(
				context.getResources().getString(R.string.trackmgr_exporting)
				.replace("{0}", Long.toString(trackId)));
		dialog.setCancelable(false);
		dialog.show();
	}
	
	
	@Override
	protected Boolean doInBackground(Void... params) {
		try {
			exportTrackAsGpx(trackId);
		} catch (ExportTrackException ete) {
			errorMsg = ete.getMessage();
			return false;
		}
		return true;
	}
	
	
	@Override
	protected void onProgressUpdate(Integer... values) {
		dialog.setProgress(values[0]);
	}

	@Override
	protected void onPostExecute(Boolean success) {
		dialog.dismiss();
		if (!success) {
			new AlertDialog.Builder(context)
				.setTitle(android.R.string.dialog_alert_title)
				.setMessage(context.getResources()
						.getString(R.string.trackmgr_export_error)
						.replace("{0}", errorMsg))
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setNeutralButton(android.R.string.ok, new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();						
					}
				})
				.show();
		}
	}

	private void exportTrackAsGpx(long trackId) throws ExportTrackException {
		File sdRoot = Environment.getExternalStorageDirectory();
		
		// The location that the user has specified gpx files 
		// and associated content to be written
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String userGPXExportDirectoryName = prefs.getString(
				OSMTracker.Preferences.KEY_STORAGE_DIR,	OSMTracker.Preferences.VAL_STORAGE_DIR);
				
		if (sdRoot.canWrite()) {
			ContentResolver cr = context.getContentResolver();
			Cursor c = cr.query(ContentUris.withAppendedId(
					TrackContentProvider.CONTENT_URI_TRACK, trackId), null, null,
					null, null);

			// Get the startDate of this track
			// TODO: Maybe we should be pulling the track name instead?
			// We'd need to consider the possibility that two tracks were given the same name
			// We could possibly disambiguate by including the track ID in the Folder Name
			// to avoid overwriting another track on one hand or needlessly creating additional
			// directories to avoid overwriting.
			Date startDate = new Date();
			if (null != c && 1 <= c.getCount()) {
				c.moveToFirst();
				long startDateInMilliseconds = c.getLong(c.getColumnIndex(Schema.COL_START_DATE));
				startDate.setTime(startDateInMilliseconds);
			}
			String filenameBase = buildGPXFilename(c);
			c.close();

			boolean directoryPerTrack = prefs.getBoolean(OSMTracker.Preferences.KEY_OUTPUT_DIR_PER_TRACK, 
					OSMTracker.Preferences.VAL_OUTPUT_GPX_OUTPUT_DIR_PER_TRACK);
					
			// Create the path to the directory to which we will be writing
			// Trim the directory name, as additional spaces at the end will 
			// not allow the directory to be created if required
			String exportDirectoryPath = userGPXExportDirectoryName.trim();
			String perTrackDirectory = "";
			if (directoryPerTrack) {
				// If the user wants a directory per track, then create a name for the destination directory
				// based on the start date of the track
				perTrackDirectory = File.separator + DataHelper.FILENAME_FORMATTER.format(startDate);
			}
			
			// Create a file based on the path we've generated above
			File trackGPXExportDirectory = new File(sdRoot + exportDirectoryPath + perTrackDirectory);

			// Create track directory if needed
			if (! trackGPXExportDirectory.exists()) {
				if (! trackGPXExportDirectory.mkdirs()) {
					Log.w(TAG,"Failed to create directory [" 
							+trackGPXExportDirectory.getAbsolutePath()+ "]");
				}
				
				if (! trackGPXExportDirectory.exists()) {
					// Specific hack for Google Nexus (See issue #168)
					if (android.os.Build.MODEL.equals(OSMTracker.Devices.NEXUS_S)) {
						// exportDirectoryPath always starts with "/"
						trackGPXExportDirectory = new File(exportDirectoryPath + perTrackDirectory);
						trackGPXExportDirectory.mkdirs();
					}
				}
				
				if (! trackGPXExportDirectory.exists()) {
					throw new ExportTrackException(context.getResources().getString(R.string.error_create_track_dir,
							trackGPXExportDirectory.getAbsolutePath()));
				}
			}

			File trackFile = new File(trackGPXExportDirectory, filenameBase);

			Cursor cTrackPoints = cr.query(TrackContentProvider.trackPointsUri(trackId), null,
					null, null, Schema.COL_TIMESTAMP + " asc");
			Cursor cWayPoints = cr.query(TrackContentProvider.waypointsUri(trackId), null, null,
					null, Schema.COL_TIMESTAMP + " asc");

			if (null != cTrackPoints && null != cWayPoints) {
				dialog.setIndeterminate(false);
				dialog.setProgress(0);
				dialog.setMax(cTrackPoints.getCount() + cWayPoints.getCount());
				
				try {
					writeGpxFile(cTrackPoints, cWayPoints, trackFile);
					copyWaypointFiles(trackGPXExportDirectory);
					DataHelper.setTrackExportDate(trackId, System.currentTimeMillis(), cr);
				} catch (IOException ioe) {
					throw new ExportTrackException(ioe.getMessage());
				} finally {
					cTrackPoints.close();
					cWayPoints.close();
				}
			}
		} else {
			throw new ExportTrackException(context.getResources().getString(R.string.error_externalstorage_not_writable));
		}
	}

	/**
	 * Characters to replace in track filename, for use by {@link #buildGPXFilename(Cursor)}. <BR>
	 * The characters are: (space) ' " / \ * ? ~ @ &lt; &gt; <BR>
	 * In addition, ':' will be replaced by ';', before calling this pattern.
	 */
	private final static Pattern FILENAME_CHARS_BLACKLIST_PATTERN =
		Pattern.compile("[ '\"/\\\\*?~@<>]");  // must double-escape \

	/**
	 * Build GPX filename from track info, based on preferences.
	 * The filename will have the start date, and/or the track name if available.
	 * If no name is available, fall back to the start date and time.
	 * Track name characters will be sanitized using {@link #FILENAME_CHARS_BLACKLIST_PATTERN}.
	 * @param c  Track info: {@link Schema#COL_NAME}, {@link Schema#COL_START_DATE}
	 * @return  GPX filename, not including the path
	 */
	private String buildGPXFilename(Cursor c) {
		// Build GPX filename from track info & preferences
		final String filenameOutput = PreferenceManager.getDefaultSharedPreferences(context).getString(
				OSMTracker.Preferences.KEY_OUTPUT_FILENAME,
				OSMTracker.Preferences.VAL_OUTPUT_FILENAME);
		StringBuffer filenameBase = new StringBuffer();
		final int colName = c.getColumnIndex(Schema.COL_NAME);
		if ((! c.isNull(colName))
			&& (! filenameOutput.equals(OSMTracker.Preferences.VAL_OUTPUT_FILENAME_DATE)))
		{
			final String tname_raw =
				c.getString(colName).trim().replace(':', ';');
			final String sanitized =
				FILENAME_CHARS_BLACKLIST_PATTERN.matcher(tname_raw).replaceAll("_");
			filenameBase.append(sanitized);
		}
		if ((filenameBase.length() == 0)
			|| ! filenameOutput.equals(OSMTracker.Preferences.VAL_OUTPUT_FILENAME_NAME))
		{
			final long startDate = c.getLong(c.getColumnIndex(Schema.COL_START_DATE));
			if (filenameBase.length() > 0)
				filenameBase.append('_');
			filenameBase.append(DataHelper.FILENAME_FORMATTER.format(new Date(startDate)));
		}
		filenameBase.append(DataHelper.EXTENSION_GPX);
		return filenameBase.toString();
	}
	
	/**
	 * Writes the GPX file
	 * @param cTrackPoints Cursor to track points.
	 * @param cWayPoints Cursor to way points.
	 * @param target Target GPX file
	 * @throws IOException 
	 */
	private void writeGpxFile(Cursor cTrackPoints, Cursor cWayPoints, File target) throws IOException {
		
		String accuracyOutput = PreferenceManager.getDefaultSharedPreferences(context).getString(
				OSMTracker.Preferences.KEY_OUTPUT_ACCURACY,
				OSMTracker.Preferences.VAL_OUTPUT_ACCURACY);
		boolean fillHDOP = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
				OSMTracker.Preferences.KEY_OUTPUT_GPX_HDOP_APPROXIMATION,
				OSMTracker.Preferences.VAL_OUTPUT_GPX_HDOP_APPROXIMATION);
		
		Writer writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(target));
			
			writer.write(XML_HEADER + "\n");
			writer.write(TAG_GPX + "\n");
			
			writeTrackPoints(context.getResources().getString(R.string.gpx_track_name), writer, cTrackPoints, fillHDOP);
			writeWayPoints(writer, cWayPoints, accuracyOutput, fillHDOP);
			
			writer.write("</gpx>");
		} finally {
			if (writer != null) {
				writer.close();
			}
		}
	}
	
	/**
	 * Iterates on track points and write them.
	 * @param trackName Name of the track (metadata).
	 * @param fw Writer to the target file.
	 * @param c Cursor to track points.
	 * @param fillHDOP Indicates whether fill <hdop> tag with approximation from location accuracy.
	 * @throws IOException
	 */
	private void writeTrackPoints(String trackName, Writer fw, Cursor c, boolean fillHDOP) throws IOException {
		// Update dialog every 1%
		int dialogUpdateThreshold = c.getCount() / 100;
		if (dialogUpdateThreshold == 0) {
			dialogUpdateThreshold++;
		}
		
		fw.write("\t" + "<trk>" + "\n");
		fw.write("\t\t" + "<name>" + CDATA_START + trackName + CDATA_END + "</name>" + "\n");
		if (fillHDOP) {
			fw.write("\t\t" + "<cmt>"
					+ CDATA_START
					+ context.getResources().getString(R.string.gpx_hdop_approximation_cmt)
					+ CDATA_END
					+ "</cmt>" + "\n");
		}
		
		fw.write("\t\t" + "<trkseg>" + "\n");
		
		int i=0;
		for(c.moveToFirst(); !c.isAfterLast(); c.moveToNext(),i++) {
			StringBuffer out = new StringBuffer();
			out.append("\t\t\t" + "<trkpt lat=\"" 
					+ c.getDouble(c.getColumnIndex(Schema.COL_LATITUDE)) + "\" "
					+ "lon=\"" + c.getDouble(c.getColumnIndex(Schema.COL_LONGITUDE)) + "\">" + "\n");
	        if (! c.isNull(c.getColumnIndex(Schema.COL_ELEVATION))) {
	        	out.append("\t\t\t\t" + "<ele>" + c.getDouble(c.getColumnIndex(Schema.COL_ELEVATION)) + "</ele>" + "\n");
	        }
	        out.append("\t\t\t\t" + "<time>" + POINT_DATE_FORMATTER.format(new Date(c.getLong(c.getColumnIndex(Schema.COL_TIMESTAMP)))) + "</time>" + "\n");
	        
	        if(fillHDOP && ! c.isNull(c.getColumnIndex(Schema.COL_ACCURACY))) {
	        	out.append("\t\t\t\t" + "<hdop>" + (c.getDouble(c.getColumnIndex(Schema.COL_ACCURACY)) / OSMTracker.HDOP_APPROXIMATION_FACTOR) + "</hdop>" + "\n");
	        }
	       
	        out.append("\t\t\t" + "</trkpt>" + "\n");
	        fw.write(out.toString());

	        if (i % dialogUpdateThreshold == 0) {
		    	dialog.incrementProgressBy(dialogUpdateThreshold);
		    }
		}
		
		fw.write("\t\t" + "</trkseg>" + "\n");
		fw.write("\t" + "</trk>" + "\n");
	}
	
	/**
	 * Iterates on way points and write them.
	 * @param fw Writer to the target file.
	 * @param c Cursor to way points.
	 * @param accuracyInfo Constant describing how to include (or not) accuracy info for way points.
	 * @param fillHDOP Indicates whether fill <hdop> tag with approximation from location accuracy.
	 * @throws IOException
	 */
	private void writeWayPoints(Writer fw, Cursor c, String accuracyInfo, boolean fillHDOP) throws IOException {

		// Update dialog every 1%
		int dialogUpdateThreshold = c.getCount() / 100;
		if (dialogUpdateThreshold == 0) {
			dialogUpdateThreshold++;
		}
		
		// Label for meter unit
		String meterUnit = context.getResources().getString(R.string.various_unit_meters);
		// Word "accuracy"
		String accuracy = context.getResources().getString(R.string.various_accuracy);
		
		int i=0;
		for(c.moveToFirst(); !c.isAfterLast(); c.moveToNext(), i++) {
			StringBuffer out = new StringBuffer();
			out.append("\t" + "<wpt lat=\""
					+ c.getDouble(c.getColumnIndex(Schema.COL_LATITUDE)) + "\" "
					+ "lon=\"" + c.getDouble(c.getColumnIndex(Schema.COL_LONGITUDE)) + "\">" + "\n");
	        if (! c.isNull(c.getColumnIndex(Schema.COL_ELEVATION))) {
	        	out.append("\t\t" + "<ele>" + c.getDouble(c.getColumnIndex(Schema.COL_ELEVATION)) + "</ele>" + "\n");
	        }
		    out.append("\t\t" + "<time>" + POINT_DATE_FORMATTER.format(new Date(c.getLong(c.getColumnIndex(Schema.COL_TIMESTAMP)))) + "</time>" + "\n");

		    if(fillHDOP && ! c.isNull(c.getColumnIndex(Schema.COL_ACCURACY))) {
	        	out.append("\t\t" + "<hdop>" + (c.getDouble(c.getColumnIndex(Schema.COL_ACCURACY)) / OSMTracker.HDOP_APPROXIMATION_FACTOR) + "</hdop>" + "\n");
	        }
		    
		    String name = c.getString(c.getColumnIndex(Schema.COL_NAME));
		    
		    if (! OSMTracker.Preferences.VAL_OUTPUT_ACCURACY_NONE.equals(accuracyInfo) && ! c.isNull(c.getColumnIndex(Schema.COL_ACCURACY))) {
		    	// Outputs accuracy info for way point
		    	if (OSMTracker.Preferences.VAL_OUTPUT_ACCURACY_WPT_NAME.equals(accuracyInfo)) {
		    		// Output accuracy with name
		    		out.append("\t\t" + "<name>"
		    				+ CDATA_START 
		    				+ name
		    				+ " (" + c.getDouble(c.getColumnIndex(Schema.COL_ACCURACY)) + meterUnit + ")"
		    				+ CDATA_END
		    				+ "</name>" + "\n");
		    	} else if (OSMTracker.Preferences.VAL_OUTPUT_ACCURACY_WPT_CMT.equals(accuracyInfo)) {
		    		// Output accuracy in separate tag
		    		out.append("\t\t" + "<name>" + CDATA_START + name + CDATA_END + "</name>" + "\n");
		    		out.append("\t\t" + "<cmt>" + CDATA_START + accuracy + ": " + c.getDouble(c.getColumnIndex(Schema.COL_ACCURACY)) + meterUnit + CDATA_END + "</cmt>" + "\n");
		    	} else {
		    		// Unknown value for accuracy info, shouldn't occur but who knows ?
		    		// See issue #68. Output at least the name just in case.
		    		out.append("\t\t" + "<name>" + CDATA_START + name + CDATA_END + "</name>" + "\n");
		    	}
		    } else {
		    	// No accuracy info requested, or available
		    	out.append("\t\t" + "<name>" + CDATA_START + name + CDATA_END + "</name>" + "\n");
		    }
			
		    String link = c.getString(c.getColumnIndex(Schema.COL_LINK));
		    if (link != null) {
		       	out.append("\t\t" + "<link href=\"" + URLEncoder.encode(link) + "\">" + "\n");
		       	out.append("\t\t\t" + "<text>" + link +"</text>\n");
		       	out.append("\t\t" + "</link>" + "\n");
		    }
		    
		    if (! c.isNull(c.getColumnIndex(Schema.COL_NBSATELLITES))) {
		    	out.append("\t\t" + "<sat>" + c.getInt(c.getColumnIndex(Schema.COL_NBSATELLITES)) + "</sat>" + "\n");
		    }
		    
		    out.append("\t" + "</wpt>" + "\n");
		    
		    fw.write(out.toString());

		    if (i % dialogUpdateThreshold == 0) {
		    	dialog.incrementProgressBy(dialogUpdateThreshold);
		    }
		}
	}

	/**
	 * Copy all files from the OSMTracker external storage location to gpxOutputDirectory
	 * @param gpxOutputDirectory The directory to which the track is being exported
	 */
	private void copyWaypointFiles(File gpxOutputDirectory) {
		// Get the new location where files related to these waypoints are/should be stored		
		File trackDir = DataHelper.getTrackDirectory(trackId);

		if(trackDir != null){
			Log.v(TAG, "Copying files from the standard TrackDir ["+trackDir+"] to the export directory ["+gpxOutputDirectory+"]");
			FileSystemUtils.copyDirectoryContents(gpxOutputDirectory, trackDir);
		}
		
	}
	


}
