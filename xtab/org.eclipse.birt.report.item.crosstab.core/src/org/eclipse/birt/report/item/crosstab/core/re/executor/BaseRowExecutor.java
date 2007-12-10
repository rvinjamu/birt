/*******************************************************************************
 * Copyright (c) 2007 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *******************************************************************************/

package org.eclipse.birt.report.item.crosstab.core.re.executor;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.olap.OLAPException;
import javax.olap.cursor.DimensionCursor;
import javax.olap.cursor.EdgeCursor;

import org.eclipse.birt.report.engine.extension.IReportItemExecutor;
import org.eclipse.birt.report.item.crosstab.core.de.AggregationCellHandle;
import org.eclipse.birt.report.item.crosstab.core.de.DimensionViewHandle;
import org.eclipse.birt.report.item.crosstab.core.de.LevelViewHandle;
import org.eclipse.birt.report.item.crosstab.core.i18n.Messages;

/**
 * This class is the base class for all measure detail row executors.
 */
public abstract class BaseRowExecutor extends BaseCrosstabExecutor
{

	private static Logger logger = Logger.getLogger( CrosstabMeasureRowExecutor.class.getName( ) );

	protected int rowIndex;
	protected long currentEdgePosition;

	protected int rowSpan, colSpan;
	protected int currentChangeType;
	protected int currentColIndex;
	protected int lastMeasureIndex;
	protected int lastDimensionIndex;
	protected int lastLevelIndex;
	protected int totalMeasureCount;

	protected boolean measureDetailStarted;
	protected boolean measureSubTotalStarted;
	protected boolean measureGrandTotalStarted;

	protected boolean hasLast;
	protected boolean isFirst;

	protected IReportItemExecutor nextExecutor;

	protected BaseRowExecutor( BaseCrosstabExecutor parent, int rowIndex )
	{
		super( parent );

		this.rowIndex = rowIndex;
	}

	public void close( )
	{
		super.close( );

		nextExecutor = null;
	}

	protected void prepareChildren( )
	{
		currentChangeType = ColumnEvent.UNKNOWN_CHANGE;
		currentColIndex = -1;

		currentEdgePosition = -1;

		rowSpan = 1;
		colSpan = 0;
		lastMeasureIndex = -1;
		totalMeasureCount = crosstabItem.getMeasureCount( );

		measureDetailStarted = false;
		measureSubTotalStarted = false;
		measureGrandTotalStarted = false;

		hasLast = false;
		isFirst = true;
	}

	protected AggregationCellHandle getAggregationCell( int rowDimensionIndex,
			int rowLevelIndex, int colDimensionIndex, int colLevelIndex,
			int measureIndex )
	{
		if ( measureIndex >= 0 && measureIndex < totalMeasureCount )
		{
			String rdName = null;
			String rlName = null;
			String cdName = null;
			String clName = null;

			if ( rowDimensionIndex >= 0 && rowLevelIndex >= 0 )
			{
				DimensionViewHandle rdv = crosstabItem.getDimension( ROW_AXIS_TYPE,
						rowDimensionIndex );
				LevelViewHandle rlv = rdv.getLevel( rowLevelIndex );

				rdName = rdv.getCubeDimensionName( );
				rlName = rlv.getCubeLevelName( );
			}

			if ( colDimensionIndex >= 0 || colLevelIndex >= 0 )
			{
				DimensionViewHandle cdv = crosstabItem.getDimension( COLUMN_AXIS_TYPE,
						colDimensionIndex );
				LevelViewHandle clv = cdv.getLevel( colLevelIndex );

				cdName = cdv.getCubeDimensionName( );
				clName = clv.getCubeLevelName( );
			}

			return crosstabItem.getMeasure( measureIndex )
					.getAggregationCell( rdName, rlName, cdName, clName );
		}

		return null;
	}

	protected boolean checkMeasureVerticalSpanOverlapped( ColumnEvent ev )
	{
		return false;
	}

	protected boolean isMeasureGrandTotalNeedStart( ColumnEvent ev )
	{
		if ( measureDetailStarted
				|| measureSubTotalStarted
				|| measureGrandTotalStarted
				|| ev.type != ColumnEvent.GRAND_TOTAL_CHANGE )
		{
			return false;
		}

		if ( checkMeasureVerticalSpanOverlapped( ev ) )
		{
			return false;
		}

		return true;
	}

	protected boolean isMeasureSubTotalNeedStart( ColumnEvent ev )
	{
		if ( measureDetailStarted
				|| measureSubTotalStarted
				|| ev.type != ColumnEvent.COLUMN_TOTAL_CHANGE )
		{
			return false;
		}

		if ( checkMeasureVerticalSpanOverlapped( ev ) )
		{
			return false;
		}

		return true;
	}

	protected boolean isMeasureDetailNeedStart( ColumnEvent ev )
	{
		if ( measureDetailStarted
				|| ( ev.type != ColumnEvent.MEASURE_CHANGE && ev.type != ColumnEvent.COLUMN_EDGE_CHANGE ) )
		{
			return false;
		}

		if ( checkMeasureVerticalSpanOverlapped( ev ) )
		{
			return false;
		}

		return true;
	}

	protected boolean isMeetMeasureDetailEnd( ColumnEvent ev,
			AggregationCellHandle aggCell )
	{
		// TODO for multiple measures, we currently disable span over feature.
		if ( totalMeasureCount != 1
		// totalMeasureCount <= 0
				|| aggCell == null
				|| ev.type == ColumnEvent.GRAND_TOTAL_CHANGE )
		{
			// 1. for empty measures, dummy cell always span 1.
			// 2. for grand total event, always ends last span.
			return true;
		}

		int targetColSpanGroupIndex = GroupUtil.getGroupIndex( columnGroups,
				aggCell.getSpanOverOnColumn( ) );

		if ( targetColSpanGroupIndex != -1 )
		{
			if ( ev.type == ColumnEvent.COLUMN_TOTAL_CHANGE )
			{
				// check subtotal event, overwrite child level sub totals
				EdgeGroup gp = (EdgeGroup) columnGroups.get( targetColSpanGroupIndex );

				if ( ev.dimensionIndex < gp.dimensionIndex
						|| ( ev.dimensionIndex == gp.dimensionIndex && ev.levelIndex < gp.levelIndex ) )
				{
					return true;
				}
			}

			try
			{
				// use preview group cursor to check edge end.
				targetColSpanGroupIndex--;

				if ( targetColSpanGroupIndex == -1 )
				{
					// this is the outer-most level, it never ends until whole
					// edge ends.
					return false;
				}
				else
				{
					EdgeCursor columnEdgeCursor = getColumnEdgeCursor( );

					if ( columnEdgeCursor != null )
					{
						columnEdgeCursor.setPosition( ev.dataPosition );

						DimensionCursor dc = (DimensionCursor) columnEdgeCursor.getDimensionCursor( )
								.get( targetColSpanGroupIndex );

						if ( !GroupUtil.isDummyGroup( dc ) )
						{
							return currentEdgePosition < dc.getEdgeStart( );
						}
					}
				}
			}
			catch ( OLAPException e )
			{
				logger.log( Level.SEVERE,
						Messages.getString( "CrosstabRowExecutor.error.check.edge.start" ), //$NON-NLS-1$
						e );
			}
		}

		return true;
	}

	abstract protected void advance( );

	public IReportItemExecutor getNextChild( )
	{
		IReportItemExecutor childExecutor = nextExecutor;

		nextExecutor = null;

		advance( );

		return childExecutor;
	}

	public boolean hasNextChild( )
	{
		if ( isFirst )
		{
			isFirst = false;

			advance( );
		}

		return nextExecutor != null;
	}

}
